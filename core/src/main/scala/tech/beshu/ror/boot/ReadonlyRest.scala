/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.boot

import cats.Show
import cats.data.{EitherT, NonEmptyList}
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.audit.sink.AuditSinkServiceCreator
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.domain.RorSettingsIndex
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, Core, CoreFactory, RawRorSettingsBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest.*
import tech.beshu.ror.configuration.*
import tech.beshu.ror.es.{EsEnv, IndexDocumentManager}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant

class ReadonlyRest(coreFactory: CoreFactory,
                   indexDocumentManager: IndexDocumentManager,
                   auditSinkServiceCreator: AuditSinkServiceCreator)
                  (implicit systemContext: SystemContext,
                   scheduler: Scheduler)
  extends Logging {

  private[boot] val authServicesMocksProvider = new MutableMocksProviderWithCachePerRequest(AuthServicesMocks.empty)

  def start(esConfigBasedRorSettings: EsConfigBasedRorSettings): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      creatorsAndLoaders <- lift(SettingsRelatedCreatorsAndLoaders.create(esConfigBasedRorSettings, indexDocumentManager))
      loadedSettings <- EitherT(creatorsAndLoaders.startingRorSettingsLoader.load())
      (loadedMainRorSettings, loadedTestRorSettings) = loadedSettings
      instance <- startRor(esConfigBasedRorSettings, creatorsAndLoaders.creators, loadedMainRorSettings, loadedTestRorSettings)
    } yield instance).value
  }

  private def startRor(esConfigBasedRorSettings: EsConfigBasedRorSettings,
                       creators: SettingsRelatedCreators,
                       loadedMainRorSettings: MainRorSettings,
                       loadedTestRorSettings: Option[TestRorSettings]) = {
    for {
      mainEngine <- EitherT(loadRorEngine(loadedMainRorSettings.rawSettings, esConfigBasedRorSettings.settingsIndex))
      testEngine <- EitherT.right(loadTestEngine(loadedTestRorSettings, esConfigBasedRorSettings.settingsIndex))
      rorInstance <- createRorInstance(esConfigBasedRorSettings, creators, mainEngine, testEngine, loadedMainRorSettings)
    } yield rorInstance
  }

  private def loadTestEngine(loadedTestRorSettings: Option[TestRorSettings],
                             settingsIndex: RorSettingsIndex) = {
    loadedTestRorSettings match {
      case None =>
        Task.now(TestEngine.NotConfigured)
      case Some(settings) if !settings.isExpired(systemContext.clock) =>
        loadActiveTestEngine(settingsIndex, settings)
      case Some(settings) =>
        loadInvalidatedTestEngine(settings)
    }
  }

  private def loadActiveTestEngine(settingsIndex: RorSettingsIndex, testSettings: TestRorSettings) = {
    for {
      _ <- Task.delay(authServicesMocksProvider.update(testSettings.mocks))
      testEngine <- loadRorEngine(testSettings.rawSettings, settingsIndex)
        .map {
          case Right(loadedEngine) =>
            TestEngine.Configured(
              engine = loadedEngine,
              settings = testSettings.rawSettings,
              expiration = expirationFrom(testSettings.expiration)
            )
          case Left(startingFailure) =>
            logger.error(s"Unable to start test engine. Cause: ${startingFailure.message.show}. Test settings engine will be marked as invalidated.")
            TestEngine.Invalidated(testSettings.rawSettings, expirationFrom(testSettings.expiration))
        }
    } yield testEngine
  }

  private def loadInvalidatedTestEngine(testSettings: TestRorSettings) = {
    Task
      .delay(authServicesMocksProvider.update(testSettings.mocks))
      .map { _ =>
        TestEngine.Invalidated(testSettings.rawSettings, expirationFrom(testSettings.expiration))
      }
  }

  private def expirationFrom(expiration: TestRorSettings.Expiration): TestEngine.Expiration = {
    TestEngine.Expiration(expiration.ttl, expiration.validTo)
  }

  private def createRorInstance(esConfigBasedRorSettings: EsConfigBasedRorSettings,
                                creators: SettingsRelatedCreators,
                                mainEngine: Engine,
                                testEngine: TestEngine,
                                alreadyLoadedSettings: MainRorSettings) = {
    EitherT.right[StartingFailure] {
      RorInstance.create(this, esConfigBasedRorSettings, creators, MainEngine(mainEngine, alreadyLoadedSettings.rawSettings), testEngine)
    }
  }

  private[ror] def loadRorEngine(settings: RawRorSettings,
                                 settingsIndex: RorSettingsIndex): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

    EitherT(
      coreFactory
        .createCoreFrom(settings, settingsIndex, httpClientsFactory, ldapConnectionPoolProvider, authServicesMocksProvider)
    )
      .flatMap(core => createEngine(httpClientsFactory, ldapConnectionPoolProvider, core))
      .semiflatTap { engine =>
        Task(inspectFlsEngine(engine))
      }
      .leftMap(handleLoadingCoreErrors)
      .value
  }

  private def createEngine(httpClientsFactory: AsyncHttpClientsFactory,
                           ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                           core: Core): EitherT[Task, NonEmptyList[CoreCreationError], Engine] = {
    implicit val loggingContext: LoggingContext = LoggingContext(core.accessControl.staticContext.obfuscatedHeaders)
    EitherT(createAuditingTool(core.auditingSettings))
      .map { auditingTool =>
        val decoratedCore = Core(
          accessControl = new AccessControlListLoggingDecorator(
            underlying = core.accessControl,
            auditingTool = auditingTool
          ),
          dependencies = core.dependencies,
          auditingSettings = core.auditingSettings
        )
        new Engine(
          core = decoratedCore,
          httpClientsFactory = httpClientsFactory,
          ldapConnectionPoolProvider,
          auditingTool
        )
      }
  }

  private def createAuditingTool(auditingSettings: Option[AuditingTool.Settings])
                                (implicit loggingContext: LoggingContext): Task[Either[NonEmptyList[CoreCreationError], Option[AuditingTool]]] = {
    auditingSettings
      .map(settings => AuditingTool.create(settings, auditSinkServiceCreator)(using systemContext.clock, loggingContext))
      .sequence
      .map {
        _.sequence
          .map(_.flatten)
          .leftMap {
            _.map(creationError => CoreCreationError.AuditingSettingsCreationError(Message(creationError.message)))
          }
      }
  }

  private def inspectFlsEngine(engine: Engine): Unit = {
    engine.core.accessControl.staticContext.usedFlsEngineInFieldsRule.foreach {
      case FlsEngine.Lucene | FlsEngine.ESWithLucene =>
        logger.warn("Defined fls engine relies on lucene. To make it work well, all nodes should have ROR plugin installed.")
      case FlsEngine.ES =>
        logger.warn("Defined fls engine relies on ES only. This engine doesn't provide full FLS functionality hence some requests may be rejected.")
    }
  }

  private def handleLoadingCoreErrors(errors: NonEmptyList[RawRorSettingsBasedCoreFactory.CoreCreationError]) = {
    val errorsMessage = errors
      .map(_.reason)
      .map {
        case Reason.Message(msg) => msg
        case Reason.MalformedValue(yamlString) => s"Malformed settings: ${yamlString.show}"
      }
      .toList
      .mkString("Errors:\n", "\n", "")
    StartingFailure(errorsMessage)
  }

  private def lift[A](value: => A) = {
    EitherT.liftF(Task.delay(value))
  }
}

object ReadonlyRest {

  // todo: move somewhere else
  final case class StartingFailure(message: String, throwable: Option[Throwable] = None)
  object StartingFailure {
    // todo: move?
    implicit val show: Show[StartingFailure] = Show.show(_.message)
  }

  final case class MainEngine(engine: Engine,
                              settings: RawRorSettings)

  sealed trait TestEngine

  object TestEngine {
    object NotConfigured extends TestEngine

    final case class Configured(engine: Engine,
                                settings: RawRorSettings,
                                expiration: Expiration) extends TestEngine

    final case class Invalidated(settings: RawRorSettings,
                                 expiration: Expiration) extends TestEngine

    final case class Expiration(ttl: PositiveFiniteDuration, validTo: Instant)
  }

  final class Engine(val core: Core,
                     httpClientsFactory: AsyncHttpClientsFactory,
                     ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                     auditingTool: Option[AuditingTool])
                    (implicit scheduler: Scheduler) {

    private[ror] def shutdown(): Unit = {
      httpClientsFactory.shutdown()
      ldapConnectionPoolProvider.close().runAsyncAndForget
      auditingTool.foreach(_.close().runAsyncAndForget)
    }
  }

  // todo: do we need both?
  def create(indexContentService: IndexDocumentManager,
             auditSinkServiceCreator: AuditSinkServiceCreator,
             env: EsEnv)
            (implicit scheduler: Scheduler,
             systemContext: SystemContext): ReadonlyRest = {
    val coreFactory: CoreFactory = new RawRorSettingsBasedCoreFactory(env.esVersion)
    create(coreFactory, indexContentService, auditSinkServiceCreator)
  }

  def create(coreFactory: CoreFactory,
             indexDocumentManager: IndexDocumentManager,
             auditSinkServiceCreator: AuditSinkServiceCreator)
            (implicit scheduler: Scheduler,
             systemContext: SystemContext): ReadonlyRest = {
    new ReadonlyRest(coreFactory, indexDocumentManager, auditSinkServiceCreator)
  }
}