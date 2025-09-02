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

import cats.data.{EitherT, NonEmptyList}
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.audit.sink.AuditSinkServiceCreator
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, Core, CoreFactory, RawRorSettingsBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest.*
import tech.beshu.ror.configuration.*
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy
import tech.beshu.ror.es.{EsEnv, IndexJsonContentService}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.source.{FileSettingsSource, IndexSettingsSource}
import tech.beshu.ror.settings.strategy.RorMainSettingsIndexWithFileFallbackLoadingStrategy.LoadingError
import tech.beshu.ror.settings.strategy.{RorMainSettingsIndexWithFileFallbackLoadingStrategy, TestSettingsIndexOnlyLoadingStrategy}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant

class ReadonlyRest(coreFactory: CoreFactory,
                   indexContentService: IndexJsonContentService,
                   auditSinkServiceCreator: AuditSinkServiceCreator,
                   val esEnv: EsEnv)
                  (implicit systemContext: SystemContext,
                   scheduler: Scheduler)
  extends Logging {

  private[boot] val authServicesMocksProvider = new MutableMocksProviderWithCachePerRequest(AuthServicesMocks.empty)

  def start(esConfig: EsConfigBasedRorSettings): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      // todo: refactor?
      mainIndexSettingsSource <- lift {
        indexContentService.toString
        ??? : IndexSettingsSource[RawRorSettings]
      }
      mainFileSettingsSource <- lift(??? : FileSettingsSource[RawRorSettings])
      testIndexSettingsSource <- lift(??? : IndexSettingsSource[TestRorSettings])
      loadedMainRorSettings <- loadMainSettings(mainIndexSettingsSource, mainFileSettingsSource)
      loadedTestRorSettings <- loadTestSettings(esConfig, testIndexSettingsSource)
      instance <- startRor(esConfig, loadedMainRorSettings, mainIndexSettingsSource, mainFileSettingsSource, loadedTestRorSettings, testIndexSettingsSource)
    } yield instance).value
  }

  private def loadMainSettings(indexSettingsSource: IndexSettingsSource[RawRorSettings],
                               fileSettingsSource: FileSettingsSource[RawRorSettings]): EitherT[Task, StartingFailure, RawRorSettings] = {
    val settingsLoader = new RorMainSettingsIndexWithFileFallbackLoadingStrategy(indexSettingsSource, fileSettingsSource)
    EitherT(settingsLoader.load())
      .leftMap(toStartingFailure)
  }

  private def loadTestSettings(esConfig: EsConfigBasedRorSettings,
                               indexSettingsSource: IndexSettingsSource[TestRorSettings]): EitherT[Task, StartingFailure, TestRorSettings] = {
    esConfig.loadingRorCoreStrategy match {
      case LoadingRorCoreStrategy.ForceLoadingFromFile(_) =>
        EitherT.rightT[Task, StartingFailure](TestRorSettings.NotSet)
      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(parameters, _) =>
        EitherT(new TestSettingsIndexOnlyLoadingStrategy(indexSettingsSource).load())
          .leftFlatMap {
            case LoadingSettingsError.FormatError => ???
            // todo:
            //          case LoadingFromIndexError.IndexParsingError(message) =>
            //            logger.error(s"Loading ReadonlyREST test settings from index failed: ${message.show}. No test settings will be loaded.")
            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
            //          case LoadingFromIndexError.IndexUnknownStructure =>
            //            logger.error("Loading ReadonlyREST test settings from index failed: index content malformed. No test settings will be loaded.")
            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
            //          case LoadingFromIndexError.IndexNotExist =>
            //            logger.info("Loading ReadonlyREST test settings from index failed: cannot find index. No test settings will be loaded.")
            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
          }
    }
  }

  private def toStartingFailure(error: LoadingError) = {
    error match {
      case Left(LoadingSettingsError.FormatError) =>
        StartingFailure(???)
      case Right(LoadingSettingsError.FormatError) =>
        StartingFailure(???)
//      case Left(LoadingSettingsError.FileParsingError(message)) =>
//        StartingFailure(message)
//      case Left(LoadingFromFileError.FileNotExist(file)) =>
//        StartingFailure(s"Cannot find settings file: ${file.show}")
//      case Right(LoadingFromIndexError.IndexParsingError(message)) =>
//        StartingFailure(message)
//      case Right(LoadingFromIndexError.IndexUnknownStructure) =>
//        StartingFailure(s"Settings index is malformed")
//      case Right(LoadingFromIndexError.IndexNotExist) =>
//        StartingFailure(s"Settings index doesn't exist")
    }
  }

  private def startRor(esConfig: EsConfigBasedRorSettings,
                       loadedMainRorSettings: RawRorSettings,
                       mainSettingsIndexSource: IndexSettingsSource[RawRorSettings],
                       mainSettingsFileSource: FileSettingsSource[RawRorSettings],
                       loadedTestRorSettings: TestRorSettings,
                       testSettingsIndexSource: IndexSettingsSource[TestRorSettings]) = {
    for {
      mainEngine <- EitherT(loadRorEngine(loadedMainRorSettings, esConfig))
      testEngine <- EitherT.right(loadTestEngine(esConfig, loadedTestRorSettings))
      rorInstance <- createRorInstance(esConfig, mainEngine, mainSettingsIndexSource, mainSettingsFileSource, testEngine, testSettingsIndexSource, loadedMainRorSettings)
    } yield rorInstance
  }

  private def loadTestEngine(esConfig: EsConfigBasedRorSettings,
                             loadedTestRorSettings: TestRorSettings) = {
    loadedTestRorSettings match {
      case TestRorSettings.NotSet =>
        Task.now(TestEngine.NotConfigured)
      case settings: TestRorSettings.Present if !settings.isExpired(systemContext.clock) =>
        loadActiveTestEngine(esConfig, settings)
      case settings: TestRorSettings.Present =>
        loadInvalidatedTestEngine(settings)
    }
  }

  private def loadActiveTestEngine(esConfig: EsConfigBasedRorSettings, testSettings: TestRorSettings.Present) = {
    for {
      _ <- Task.delay(authServicesMocksProvider.update(testSettings.mocks))
      testEngine <- loadRorEngine(testSettings.rawSettings, esConfig)
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

  private def loadInvalidatedTestEngine(testSettings: TestRorSettings.Present) = {
    Task
      .delay(authServicesMocksProvider.update(testSettings.mocks))
      .map { _ =>
        TestEngine.Invalidated(testSettings.rawSettings, expirationFrom(testSettings.expiration))
      }
  }

  private def expirationFrom(expiration: TestRorSettings.Present.Expiration): TestEngine.Expiration = {
    TestEngine.Expiration(expiration.ttl, expiration.validTo)
  }

  private def createRorInstance(esConfig: EsConfigBasedRorSettings,
                                mainEngine: Engine,
                                mainSettingsIndexSource: IndexSettingsSource[RawRorSettings],
                                mainSettingsFileSource: FileSettingsSource[RawRorSettings],
                                testEngine: TestEngine,
                                testSettingsIndexSource: IndexSettingsSource[TestRorSettings],
                                alreadyLoadedSettings: RawRorSettings) = {
    EitherT.right[StartingFailure] {
      RorInstance.create(this, esConfig, MainEngine(mainEngine, alreadyLoadedSettings), testEngine, mainSettingsIndexSource, mainSettingsFileSource, testSettingsIndexSource)
    }
  }

  private[ror] def loadRorEngine(rorSettings: RawRorSettings,
                                 esConfig: EsConfigBasedRorSettings): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

    EitherT(
      coreFactory
        .createCoreFrom(rorSettings, esConfig.rorSettingsIndex, httpClientsFactory, ldapConnectionPoolProvider, authServicesMocksProvider)
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

  //todo: private def notSetTestRorSettings: TestRorSettings = TestRorSettings.NotSet

  private def lift[A](value: => A) = {
    EitherT.liftF(Task.delay(value))
  }
}

object ReadonlyRest {

  final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

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

  def create(indexContentService: IndexJsonContentService,
             auditSinkServiceCreator: AuditSinkServiceCreator,
             env: EsEnv)
            (implicit scheduler: Scheduler,
             systemContext: SystemContext): ReadonlyRest = {
    val coreFactory: CoreFactory = new RawRorSettingsBasedCoreFactory(env.esVersion)
    create(coreFactory, indexContentService, auditSinkServiceCreator, env)
  }

  def create(coreFactory: CoreFactory,
             indexContentService: IndexJsonContentService,
             auditSinkServiceCreator: AuditSinkServiceCreator,
             env: EsEnv)
            (implicit scheduler: Scheduler,
             systemContext: SystemContext): ReadonlyRest = {
    new ReadonlyRest(coreFactory, indexContentService, auditSinkServiceCreator, env)
  }
}