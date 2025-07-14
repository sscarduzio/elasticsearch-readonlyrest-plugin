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
import tech.beshu.ror.configuration.index.*
import tech.beshu.ror.configuration.manager.*
import tech.beshu.ror.configuration.manager.RorMainSettingsManager.LoadingFromFileError
import tech.beshu.ror.configuration.manager.SettingsManager.{LoadingError, LoadingFromIndexError}
import tech.beshu.ror.es.{EsEnv, IndexJsonContentService}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant

class ReadonlyRest(coreFactory: CoreFactory,
                   indexContentService: IndexJsonContentService,
                   auditSinkServiceCreator: AuditSinkServiceCreator,
                   val esEnv: EsEnv)
                  (implicit systemContext: SystemContext,
                   scheduler: Scheduler) extends Logging {

  private[boot] val authServicesMocksProvider = new MutableMocksProviderWithCachePerRequest(AuthServicesMocks.empty)

  def start(esConfig: EsConfigBasedRorSettings): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      rorYamlParser <- lift(new RawRorSettingsYamlParser(esConfig.loadingRorCoreStrategy.rorSettingsMaxSize))
      rorMainSettingsLoader <- lift(new RorMainSettingsManager(
        new IndexJsonContentServiceBasedIndexMainSettingsManager(esConfig.rorSettingsIndex, indexContentService, rorYamlParser)
      ))
      rorTestSettingsLoader <- lift(new RorTestSettingsManager(
        new IndexJsonContentServiceBasedIndexTestSettingsManager(esConfig.rorSettingsIndex, indexContentService, rorYamlParser)
      ))
      loadedMainRorSettings <- loadMainRorSettings(esConfig, rorMainSettingsLoader)
      loadedTestRorSettings <- loadRorTestSettings(esConfig, rorTestSettingsLoader)
      instance <- startRor(esConfig, loadedMainRorSettings, rorMainSettingsLoader, loadedTestRorSettings, rorTestSettingsLoader)
    } yield instance).value
  }

  private def loadMainRorSettings(esConfig: EsConfigBasedRorSettings,
                                  rorSettingsLoader: RorMainSettingsManager): EitherT[Task, StartingFailure, RawRorSettings] = {
    esConfig.loadingRorCoreStrategy match {
      case LoadingRorCoreStrategy.ForceLoadingFromFile(settings) =>
        EitherT(rorSettingsLoader.loadFromFile(settings))
          .leftMap(toStartingFailure)
      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(settings, fallbackSettings) =>
        EitherT(rorSettingsLoader.loadFromIndexWithFileFallback(settings, fallbackSettings))
          .leftMap(toStartingFailure)
    }
  }

  private def loadRorTestSettings(esConfig: EsConfigBasedRorSettings,
                                  rorSettingsLoader: RorTestSettingsManager): EitherT[Task, StartingFailure, TestRorSettings] = {
    esConfig.loadingRorCoreStrategy match {
      case LoadingRorCoreStrategy.ForceLoadingFromFile(_) =>
        EitherT.rightT[Task, StartingFailure](TestRorSettings.NotSet)
      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(parameters, _) =>
        EitherT {
          rorSettingsLoader.loadFromIndexWithFallback(
            loadFromIndexParameters = parameters,
            fallbackSettings = notSetTestRorSettings
          )
        }.leftFlatMap {
          case LoadingFromIndexError.IndexParsingError(message) =>
            logger.error(s"Loading ReadonlyREST test settings from index failed: ${message.show}. No test settings will be loaded.")
            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
          case LoadingFromIndexError.IndexUnknownStructure =>
            logger.error("Loading ReadonlyREST test settings from index failed: index content malformed. No test settings will be loaded.")
            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
          case LoadingFromIndexError.IndexNotExist =>
            logger.info("Loading ReadonlyREST test settings from index failed: cannot find index. No test settings will be loaded.")
            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
        }
    }
  }

  private def toStartingFailure(error: LoadingError) = {
    error match {
      case LoadingFromFileError.FileParsingError(message) =>
        StartingFailure(message)
      case LoadingFromFileError.FileNotExist(path) =>
        StartingFailure(s"Cannot find settings file: ${path.show}")
      case LoadingFromIndexError.IndexParsingError(message) =>
        StartingFailure(message)
      case LoadingFromIndexError.IndexUnknownStructure =>
        StartingFailure(s"Settings index is malformed")
      case LoadingFromIndexError.IndexNotExist =>
        StartingFailure(s"Settings index doesn't exist")
    }
  }

  private def startRor(esConfig: EsConfigBasedRorSettings,
                       loadedMainRorSettings: RawRorSettings,
                       mainSettingsManager: RorMainSettingsManager,
                       loadedTestRorSettings: TestRorSettings,
                       testSettingsManager: RorTestSettingsManager) = {
    for {
      mainEngine <- EitherT(loadRorEngine(loadedMainRorSettings, esConfig))
      testEngine <- EitherT.right(loadTestEngine(esConfig, loadedTestRorSettings))
      rorInstance <- createRorInstance(esConfig, mainEngine, mainSettingsManager, testEngine, testSettingsManager, loadedMainRorSettings)
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
                                mainSettingsManager: RorMainSettingsManager,
                                testEngine: TestEngine,
                                testSettingsManager: RorTestSettingsManager,
                                alreadyLoadedSettings: RawRorSettings) = {
    EitherT.right[StartingFailure] {
      val rorSettingsMaxSize = esConfig.loadingRorCoreStrategy.rorSettingsMaxSize
      esConfig.loadingRorCoreStrategy match {
        case LoadingRorCoreStrategy.ForceLoadingFromFile(settings) =>
          RorInstance.createWithoutPeriodicIndexCheck(this, esConfig, MainEngine(mainEngine, alreadyLoadedSettings), testEngine, mainSettingsManager, testSettingsManager, rorSettingsMaxSize)
        case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(settings, _) =>
          RorInstance.createWithPeriodicIndexCheck(this, esConfig, MainEngine(mainEngine, alreadyLoadedSettings), testEngine, mainSettingsManager, testSettingsManager, settings.refreshInterval, rorSettingsMaxSize)
      }
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

  private def notSetTestRorSettings: TestRorSettings = TestRorSettings.NotSet

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