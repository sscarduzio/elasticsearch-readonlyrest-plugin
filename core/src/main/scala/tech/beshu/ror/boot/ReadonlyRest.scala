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
import tech.beshu.ror.accesscontrol.audit.sink.AuditSinkServiceCreator
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, Core, CoreFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest.*
import tech.beshu.ror.configuration.*
import tech.beshu.ror.configuration.ConfigLoading.{ErrorOr, LoadRorConfig}
import tech.beshu.ror.configuration.TestConfigLoading.*
import tech.beshu.ror.configuration.index.{IndexConfigManager, IndexTestConfigManager}
import tech.beshu.ror.configuration.loader.*
import tech.beshu.ror.es.{EsEnv, IndexJsonContentService}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant

class ReadonlyRest(coreFactory: CoreFactory,
                   auditSinkServiceCreator: AuditSinkServiceCreator,
                   val indexConfigManager: IndexConfigManager,
                   val indexTestConfigManager: IndexTestConfigManager,
                   val authServicesMocksProvider: MutableMocksProviderWithCachePerRequest,
                   val esEnv: EsEnv)
                  (implicit environmentConfig: EnvironmentConfig,
                   scheduler: Scheduler) extends Logging {

  def start(): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      esConfig <- loadEsConfig()
      loadedRorConfig <- loadRorConfig(esConfig)
      loadedTestRorConfig <- loadRorTestConfig(esConfig)
      instance <- startRor(esConfig, loadedRorConfig, loadedTestRorConfig)
    } yield instance).value
  }

  private def loadEsConfig() = {
    val action = ConfigLoading.loadEsConfig(esEnv)
    runStartingFailureProgram(action)
  }

  private def loadRorConfig(esConfig: EsConfig) = {
    val loadingDelay = RorProperties.atStartupRorIndexSettingLoadingDelay(environmentConfig.propertiesProvider)
    val loadingAttemptsCount = RorProperties.atStartupRorIndexSettingsLoadingAttemptsCount(environmentConfig.propertiesProvider)
    val loadingAttemptsInterval = RorProperties.atStartupRorIndexSettingsLoadingAttemptsInterval(environmentConfig.propertiesProvider)

    val action =
      if (esConfig.rorEsLevelSettings.forceLoadRorFromFile) {
        LoadRawRorConfig.loadFromFile(esEnv.configPath)
      } else {
        LoadRawRorConfig.loadFromIndexWithFileFallback(
          configurationIndex = esConfig.rorIndex.index,
          loadingDelay = loadingDelay,
          loadingAttemptsCount = loadingAttemptsCount,
          loadingAttemptsInterval = loadingAttemptsInterval,
          fallbackConfigFilePath = esEnv.configPath
        )
      }
    runStartingFailureProgram(action)
  }

  private def loadRorTestConfig(esConfig: EsConfig): EitherT[Task, StartingFailure, LoadedTestRorConfig[TestRorConfig]] = {
    val loadingDelay = RorProperties.atStartupRorIndexSettingLoadingDelay(environmentConfig.propertiesProvider)
    val loadingAttemptsCount = RorProperties.atStartupRorIndexSettingsLoadingAttemptsCount(environmentConfig.propertiesProvider)
    val loadingAttemptsInterval = RorProperties.atStartupRorIndexSettingsLoadingAttemptsInterval(environmentConfig.propertiesProvider)
    val action = LoadRawTestRorConfig.loadFromIndexWithFallback(
      configurationIndex = esConfig.rorIndex.index,
      loadingDelay = loadingDelay,
      indexLoadingAttemptsCount = loadingAttemptsCount,
      indexLoadingAttemptsInterval = loadingAttemptsInterval,
      fallbackConfig = notSetTestRorConfig
    )
    EitherT.right(runTestProgram(action))
  }

  private def runStartingFailureProgram[A](action: LoadRorConfig[ErrorOr[A]]) = {
    val compiler = ConfigLoadingInterpreter.create(indexConfigManager)
    EitherT(action.foldMap(compiler))
      .leftMap(toStartingFailure)
  }

  private def toStartingFailure(error: LoadedRorConfig.Error) = {
    error match {
      case LoadedRorConfig.FileParsingError(message) =>
        StartingFailure(message)
      case LoadedRorConfig.FileNotExist(path) =>
        StartingFailure(s"Cannot find settings file: ${path.show}")
      case LoadedRorConfig.EsFileNotExist(path) =>
        StartingFailure(s"Cannot find elasticsearch settings file: [${path.show}]")
      case LoadedRorConfig.EsFileMalformed(path, message) =>
        StartingFailure(s"Settings file is malformed: [${path.show}], ${message.show}")
      case LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled(typeOfConfiguration) =>
        StartingFailure(s"Cannot use ROR ${typeOfConfiguration.show} when XPack Security is enabled")
      case LoadedRorConfig.IndexParsingError(message) =>
        StartingFailure(message)
      case LoadedRorConfig.IndexUnknownStructure =>
        StartingFailure(s"Settings index is malformed")
      case LoadedRorConfig.IndexNotExist =>
        StartingFailure(s"Settings index doesn't exist")
    }
  }

  private def runTestProgram(action: LoadTestRorConfig[IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]]): Task[LoadedTestRorConfig[TestRorConfig]] = {
    val compiler = TestConfigLoadingInterpreter.create(indexTestConfigManager)
    EitherT(action.foldMap(compiler))
      .leftMap {
        case LoadedTestRorConfig.IndexParsingError(message) =>
          logger.error(s"Loading ReadonlyREST test settings from index failed: ${message.show}. No test settings will be loaded.")
          LoadedTestRorConfig.FallbackConfig(notSetTestRorConfig)
        case LoadedTestRorConfig.IndexUnknownStructure =>
          logger.error("Loading ReadonlyREST test settings from index failed: index content malformed. No test settings will be loaded.")
          LoadedTestRorConfig.FallbackConfig(notSetTestRorConfig)
        case LoadedTestRorConfig.IndexNotExist =>
          logger.info("Loading ReadonlyREST test settings from index failed: cannot find index. No test settings will be loaded.")
          LoadedTestRorConfig.FallbackConfig(notSetTestRorConfig)
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
      case config: TestRorConfig.Present if !config.isExpired(environmentConfig.clock) =>
        loadActiveTestEngine(esConfig, config)
      case config: TestRorConfig.Present =>
        loadInvalidatedTestEngine(config)
    }
  }

  private def loadActiveTestEngine(esConfig: EsConfig, testConfig: TestRorConfig.Present) = {
    for {
      _ <- Task.delay(authServicesMocksProvider.update(testConfig.mocks))
      testEngine <- loadRorCore(testConfig.rawConfig, esConfig.rorIndex.index)
        .map {
          case Right(loadedEngine) =>
            TestEngine.Configured(
              engine = loadedEngine,
              config = testConfig.rawConfig,
              expiration = expirationConfig(testConfig.expiration)
            )
          case Left(startingFailure) =>
            logger.error(s"Unable to start test engine. Cause: ${startingFailure.message.show}. Test settings engine will be marked as invalidated.")
            TestEngine.Invalidated(testConfig.rawConfig, expirationConfig(testConfig.expiration))
        }
    } yield testEngine
  }

  private def loadInvalidatedTestEngine(testConfig: TestRorConfig.Present) = {
    Task
      .delay(authServicesMocksProvider.update(testConfig.mocks))
      .map { _ =>
        TestEngine.Invalidated(testConfig.rawConfig, expirationConfig(testConfig.expiration))
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

    EitherT(
      coreFactory
        .createCoreFrom(config, rorIndexNameConfiguration, httpClientsFactory, ldapConnectionPoolProvider, authServicesMocksProvider)
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
    EitherT(createAuditingTool(core))
      .map { auditingTool =>
        val decoratedCore = Core(
          accessControl = new AccessControlListLoggingDecorator(
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
  }

  private def createAuditingTool(core: Core)
                                (implicit loggingContext: LoggingContext): Task[Either[NonEmptyList[CoreCreationError], Option[AuditingTool]]] = {
    core.rorConfig.auditingSettings
      .map(settings => AuditingTool.create(settings, auditSinkServiceCreator)(using environmentConfig.clock, loggingContext))
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

  private def handleLoadingCoreErrors(errors: NonEmptyList[RawRorConfigBasedCoreFactory.CoreCreationError]) = {
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

  private def notSetTestRorConfig: TestRorConfig = TestRorConfig.NotSet
}

object ReadonlyRest {

  final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

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
             environmentConfig: EnvironmentConfig): ReadonlyRest = {
    val coreFactory: CoreFactory = new RawRorConfigBasedCoreFactory(env.esVersion)
    create(coreFactory, indexContentService, auditSinkServiceCreator, env)
  }

  def create(coreFactory: CoreFactory,
             indexContentService: IndexJsonContentService,
             auditSinkServiceCreator: AuditSinkServiceCreator,
             env: EsEnv)
            (implicit scheduler: Scheduler,
             environmentConfig: EnvironmentConfig): ReadonlyRest = {
    val indexConfigManager: IndexConfigManager = new IndexConfigManager(indexContentService)
    val indexTestConfigManager: IndexTestConfigManager = new IndexTestConfigManager(indexContentService)
    val mocksProvider = new MutableMocksProviderWithCachePerRequest(AuthServicesMocks.empty)

    new ReadonlyRest(coreFactory, auditSinkServiceCreator, indexConfigManager, indexTestConfigManager, mocksProvider, env)
  }
}