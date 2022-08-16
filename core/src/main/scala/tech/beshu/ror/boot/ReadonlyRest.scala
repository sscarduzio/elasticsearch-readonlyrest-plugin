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

import java.nio.file.Path
import java.time.{Clock, Instant}

import cats.data.{EitherT, NonEmptyList}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{MutableMocksProviderWithCachePerRequest, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, Core, CoreFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.{AccessControlLoggingDecorator, AuditingTool, LoggingContext}
import tech.beshu.ror.boot.ReadonlyRest._
import tech.beshu.ror.configuration.ConfigLoading.{ErrorOr, LoadRorConfig}
import tech.beshu.ror.configuration._
import tech.beshu.ror.configuration.index.{IndexConfigManager, IndexTestConfigManager}
import tech.beshu.ror.configuration.loader.{ConfigLoadingInterpreter, LoadRawRorConfig, LoadRawTestRorConfig, LoadedRorConfig, LoadedTestRorConfig, TestConfigLoadingInterpreter}
import tech.beshu.ror.es.{AuditSinkService, IndexJsonContentService}
import tech.beshu.ror.providers._

import scala.concurrent.duration.FiniteDuration
import scala.language.{implicitConversions, postfixOps}

class ReadonlyRest(coreFactory: CoreFactory,
                   auditSinkCreator: AuditSinkCreator,
                   val indexConfigManager: IndexConfigManager,
                   val indexTestConfigManager: IndexTestConfigManager,
                   val mocksProvider: MutableMocksProviderWithCachePerRequest,
                   val esConfigPath: Path)
                  (implicit scheduler: Scheduler,
                   envVarsProvider: EnvVarsProvider,
                   propertiesProvider: PropertiesProvider,
                   clock: Clock) extends Logging {

  def start(): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      esConfig <- loadEsConfig()
      loadedRorConfig <- loadRorConfig(esConfig)
      loadedTestRorConfig <- loadRorTestConfig(esConfig)
      instance <- startRor(esConfig, loadedRorConfig, loadedTestRorConfig)
    } yield instance).value
  }

  private def loadEsConfig() = {
    val action = ConfigLoading.loadEsConfig(esConfigPath)
    runStartingFailureProgram(action)
  }

  private def loadRorConfig(esConfig: EsConfig) = {
    val action = LoadRawRorConfig.load(esConfigPath, esConfig, esConfig.rorIndex.index)
    runStartingFailureProgram(action)
  }

  private def loadRorTestConfig(esConfig: EsConfig): EitherT[Task, StartingFailure, LoadedTestRorConfig[TestRorConfig]] = {
    val fallbackConfig: TestRorConfig = TestRorConfig.NotSet
    val action = LoadRawTestRorConfig.load(
      configurationIndex = esConfig.rorIndex.index,
      fallbackConfig = fallbackConfig
    )
    EitherT.right(runTestProgram(action, LoadedTestRorConfig.FallbackConfig(fallbackConfig)))
  }

  private def runStartingFailureProgram[A](action: LoadRorConfig[ErrorOr[A]]) = {
    val compiler = ConfigLoadingInterpreter.create(indexConfigManager, RorProperties.rorIndexSettingLoadingDelay)
    EitherT(action.foldMap(compiler))
      .leftMap(toStartingFailure)
  }

  private def toStartingFailure(error: LoadedRorConfig.Error) = {
    error match {
      case LoadedRorConfig.FileParsingError(message) =>
        StartingFailure(message)
      case LoadedRorConfig.FileNotExist(path) =>
        StartingFailure(s"Cannot find settings file: ${path.value}")
      case LoadedRorConfig.EsFileNotExist(path) =>
        StartingFailure(s"Cannot find elasticsearch settings file: [${path.value}]")
      case LoadedRorConfig.EsFileMalformed(path, message) =>
        StartingFailure(s"Settings file is malformed: [${path.value}], $message")
      case LoadedRorConfig.IndexParsingError(message) =>
        StartingFailure(message)
      case LoadedRorConfig.IndexUnknownStructure =>
        StartingFailure(s"Settings index is malformed")
    }
  }

  private def runTestProgram[A](action: TestConfigLoading.LoadTestRorConfig[TestConfigLoading.IndexErrorOr[A]],
                                fallbackConfig: A): Task[A] = {
    val compiler = TestConfigLoadingInterpreter.create(indexTestConfigManager, RorProperties.rorIndexSettingLoadingDelay)
    EitherT(action.foldMap(compiler))
      .leftMap {
        case LoadedTestRorConfig.IndexParsingError(message) =>
          logger.error(s"Loading ReadonlyREST test settings from index failed: $message. Loading fallback test settings...")
          fallbackConfig
        case LoadedTestRorConfig.IndexUnknownStructure =>
          logger.info("Loading ReadonlyREST test settings from index failed: index content malformed. Loading fallback test settings...")
          fallbackConfig
        case LoadedTestRorConfig.IndexNotExist =>
          logger.info("Loading ReadonlyREST test settings from index failed: cannot find index. Loading fallback test settings...")
          fallbackConfig
      }
      .merge
  }

  private def startRor(esConfig: EsConfig,
                       loadedConfig: LoadedRorConfig[RawRorConfig],
                       loadedTestRorConfig: LoadedTestRorConfig[TestRorConfig]) = {
    for {
      mainEngine <- EitherT(loadRorCore(loadedConfig.value, esConfig.rorIndex.index))
      testEngine <- EitherT.right(loadTestEngine(esConfig, loadedTestRorConfig))
      rorInstance <- createRorInstance(esConfig.rorIndex.index, mainEngine, testEngine, loadedConfig)
    } yield rorInstance
  }

  private def loadTestEngine(esConfig: EsConfig, loadedTestRorConfig: LoadedTestRorConfig[TestRorConfig]) = {
    loadedTestRorConfig.value match {
      case TestRorConfig.NotSet =>
        Task.now(TestEngine.NotConfigured)
      case config@TestRorConfig.Present(rawConfig, expiration) if !config.isExpired(clock) =>
        loadRorCore(rawConfig, esConfig.rorIndex.index)
          .map {
            case Right(loadedEngine) =>
              TestEngine.Configured(
                engine = loadedEngine,
                config = rawConfig,
                expiration = expirationConfig(expiration)
              )
            case Left(startingFailure) =>
              logger.error(s"Unable to start test engine. Cause: ${startingFailure.message}. Test settings engine will be marked as invalidated.")
              TestEngine.Invalidated(rawConfig, expirationConfig(expiration))
          }
      case TestRorConfig.Present(rawConfig, expiration) =>
        Task.now(TestEngine.Invalidated(rawConfig, expirationConfig(expiration)))
    }
  }

  private def expirationConfig(config: TestRorConfig.Present.ExpirationConfig): TestEngine.Expiration = {
    TestEngine.Expiration(config.ttl, config.validTo)
  }

  private def createRorInstance(rorConfigurationIndex: RorConfigurationIndex,
                                engine: Engine,
                                testEngine: TestEngine,
                                loadedConfig: LoadedRorConfig[RawRorConfig]) = {
    EitherT.right[StartingFailure] {
      loadedConfig match {
        case LoadedRorConfig.FileConfig(config) =>
          RorInstance.createWithPeriodicIndexCheck(this, MainEngine(engine, config), testEngine, rorConfigurationIndex)
        case LoadedRorConfig.ForcedFileConfig(config) =>
          RorInstance.createWithoutPeriodicIndexCheck(this, MainEngine(engine, config), testEngine, rorConfigurationIndex)
        case LoadedRorConfig.IndexConfig(_, config) =>
          RorInstance.createWithPeriodicIndexCheck(this, MainEngine(engine, config), testEngine, rorConfigurationIndex)
      }
    }
  }

  private[ror] def loadRorCore(config: RawRorConfig,
                               rorIndexNameConfiguration: RorConfigurationIndex): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

    coreFactory
      .createCoreFrom(config, rorIndexNameConfiguration, httpClientsFactory, ldapConnectionPoolProvider, mocksProvider)
      .map { result =>
        result
          .right
          .map { core =>
            val engine = createEngine(httpClientsFactory, ldapConnectionPoolProvider, core)
            inspectFlsEngine(engine)
            engine
          }
          .left
          .map(handleLoadingCoreErrors)
      }
  }

  private def createEngine(httpClientsFactory: AsyncHttpClientsFactory,
                           ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                           core: Core) = {
    implicit val loggingContext: LoggingContext = LoggingContext(core.accessControl.staticContext.obfuscatedHeaders)
    val auditingTool = createAuditingTool(core)

    val decoratedCore = Core(
      accessControl = new AccessControlLoggingDecorator(
        underlying = core.accessControl,
        auditingTool = auditingTool
      ),
      rorConfig = core.rorConfig
    )

    new Engine(
      core = decoratedCore,
      httpClientsFactory = httpClientsFactory,
      ldapConnectionPoolProvider,
      auditingTool
    )
  }

  private def createAuditingTool(core: Core)
                                (implicit loggingContext: LoggingContext): Option[AuditingTool] = {
    core.rorConfig.auditingSettings
      .map(settings => new AuditingTool(settings, auditSinkCreator(settings.auditCluster)))
  }

  private def inspectFlsEngine(engine: Engine): Unit = {
    engine.core.accessControl.staticContext.usedFlsEngineInFieldsRule.foreach {
      case FlsEngine.Lucene | FlsEngine.ESWithLucene =>
        logger.warn("Defined fls engine relies on lucene. To make it work well, all nodes should have ROR plugin installed.")
      case FlsEngine.ES =>
        logger.warn("Defined fls engine relies on ES only. This engine doesn't provide full FLS functionality hence some requests may be rejected.")
    }
  }

  private def handleLoadingCoreErrors(errors: NonEmptyList[RawRorConfigBasedCoreFactory.CoreCreationError]) = {
    val errorsMessage = errors
      .map(_.reason)
      .map {
        case Reason.Message(msg) => msg
        case Reason.MalformedValue(yamlString) => s"Malformed settings: $yamlString"
      }
      .toList
      .mkString("Errors:\n", "\n", "")
    StartingFailure(errorsMessage)
  }
}

object ReadonlyRest {
  type AuditSinkCreator = AuditCluster => AuditSinkService

  final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

  sealed trait RorMode
  object RorMode {
    case object Plugin extends RorMode
    case object Proxy extends RorMode
  }

  final case class MainEngine(engine: Engine,
                              config: RawRorConfig)

  sealed trait TestEngine
  object TestEngine {
    object NotConfigured extends TestEngine
    final case class Configured(engine: Engine,
                                config: RawRorConfig,
                                expiration: Expiration) extends TestEngine
    final case class Invalidated(config: RawRorConfig,
                                 expiration: Expiration) extends TestEngine
    final case class Expiration(ttl: FiniteDuration Refined Positive,
                                validTo: Instant)
  }

  final class Engine(val core: Core,
                     httpClientsFactory: AsyncHttpClientsFactory,
                     ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                     auditingTool: Option[AuditingTool])
                    (implicit scheduler: Scheduler) {

    private[ror] def shutdown(): Unit = {
      httpClientsFactory.shutdown()
      ldapConnectionPoolProvider.close().runAsyncAndForget
      auditingTool.foreach(_.close())
    }
  }

  def create(mode: RorMode,
             indexContentService: IndexJsonContentService,
             auditSinkCreator: AuditSinkCreator,
             esConfigPath: Path)
            (implicit scheduler: Scheduler,
             envVarsProvider: EnvVarsProvider,
             propertiesProvider: PropertiesProvider,
             clock: Clock): ReadonlyRest = {
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    val coreFactory: CoreFactory = new RawRorConfigBasedCoreFactory(mode)

    create(coreFactory, indexContentService, auditSinkCreator, esConfigPath)
  }

  def create(coreFactory: CoreFactory,
             indexContentService: IndexJsonContentService,
             auditSinkCreator: AuditSinkCreator,
             esConfigPath: Path)
            (implicit scheduler: Scheduler,
             envVarsProvider: EnvVarsProvider,
             propertiesProvider: PropertiesProvider,
             clock: Clock): ReadonlyRest = {
    val indexConfigManager: IndexConfigManager = new IndexConfigManager(indexContentService)
    val indexTestConfigManager: IndexTestConfigManager = new IndexTestConfigManager(indexContentService)
    val mocksProvider = new MutableMocksProviderWithCachePerRequest(NoOpMocksProvider)

    new ReadonlyRest(coreFactory, auditSinkCreator, indexConfigManager, indexTestConfigManager, mocksProvider, esConfigPath)
  }
}