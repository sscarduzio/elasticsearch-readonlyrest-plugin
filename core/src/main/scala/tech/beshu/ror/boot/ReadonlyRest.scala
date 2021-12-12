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
import java.time.Clock
import cats.data.EitherT
import cats.effect.Resource
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{MocksProvider, MutableMocksProviderWithCachePerRequest, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, CoreFactory, CoreSettings, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.{AccessControlLoggingDecorator, AuditingTool, LoggingContext}
import tech.beshu.ror.api.{AuthMockApi, ConfigApi}
import tech.beshu.ror.boot.engines.{Engines, ImpersonatorsReloadableEngine, MainReloadableEngine}
import tech.beshu.ror.configuration.ConfigLoading.{ErrorOr, LoadRorConfig}
import tech.beshu.ror.configuration.IndexConfigManager.SavingIndexConfigError
import tech.beshu.ror.configuration.RorProperties.RefreshInterval
import tech.beshu.ror.configuration._
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError._
import tech.beshu.ror.configuration.loader.{ConfigLoadingInterpreter, FileConfigLoader, LoadRawRorConfig, LoadedRorConfig}
import tech.beshu.ror.es.{AuditSinkService, IndexJsonContentService}
import tech.beshu.ror.providers._

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

class Ror(mode: RorMode,
          override val auditSinkCreators: AuditSinkCreators,
          override val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider,
          override val propertiesProvider: PropertiesProvider = JvmPropertiesProvider)
         (implicit override val scheduler: Scheduler)
  extends ReadonlyRest {

  override protected implicit val clock: Clock = Clock.systemUTC()

  override protected lazy val coreFactory: CoreFactory = {
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val envVarsProviderImplicit: EnvVarsProvider = envVarsProvider
    new RawRorConfigBasedCoreFactory(mode)
  }
}

trait ReadonlyRest extends Logging {

  protected def coreFactory: CoreFactory

  protected def auditSinkCreators: AuditSinkCreators

  protected implicit def scheduler: Scheduler

  protected implicit def envVarsProvider: EnvVarsProvider

  protected implicit def propertiesProvider: PropertiesProvider

  protected implicit def clock: Clock

  def start(esConfigPath: Path,
            indexContentService: IndexJsonContentService)
           (implicit envVarsProvider: EnvVarsProvider): Task[Either[StartingFailure, RorInstance]] = {
    val indexConfigManager = new IndexConfigManager(indexContentService)
    (for {
      esConfig <- loadEsConfig(esConfigPath, indexConfigManager)
      loadedRorConfig <- loadRorConfig(esConfigPath, esConfig, indexConfigManager)
      instance <- startRor(esConfigPath, esConfig, loadedRorConfig, indexConfigManager)
    } yield instance).value
  }

  private def loadEsConfig(esConfigPath: Path,
                           indexConfigManager: IndexConfigManager)
                          (implicit envVarsProvider: EnvVarsProvider) = {
    val action = ConfigLoading.loadEsConfig(esConfigPath)
    runStartingFailureProgram(indexConfigManager, action)
  }

  private def loadRorConfig(esConfigPath: Path,
                            esConfig: EsConfig,
                            indexConfigManager: IndexConfigManager)
                           (implicit envVarsProvider: EnvVarsProvider) = {
    val action = LoadRawRorConfig.load(esConfigPath, esConfig, esConfig.rorIndex.index)
    runStartingFailureProgram(indexConfigManager, action)
  }

  private def runStartingFailureProgram[A](indexConfigManager: IndexConfigManager,
                                           action: LoadRorConfig[ErrorOr[A]]) = {
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

  private def startRor(esConfigPath: Path,
                       esConfig: EsConfig,
                       loadedConfig: LoadedRorConfig[RawRorConfig],
                       indexConfigManager: IndexConfigManager) = {
    val mocksProvider = new MutableMocksProviderWithCachePerRequest(NoOpMocksProvider)
    for {
      engine <- EitherT(loadRorCore(loadedConfig.value, esConfig.rorIndex.index, mocksProvider))
      rorInstance <- createRorInstance(indexConfigManager, esConfigPath, esConfig.rorIndex.index, engine, loadedConfig, mocksProvider)
    } yield rorInstance
  }

  private def createRorInstance(indexConfigManager: IndexConfigManager,
                                esConfigPath: Path,
                                rorConfigurationIndex: RorConfigurationIndex,
                                engine: Engine,
                                loadedConfig: LoadedRorConfig[RawRorConfig],
                                mocksProvider: MutableMocksProviderWithCachePerRequest) = {
    EitherT.right[StartingFailure] {
      loadedConfig match {
        case LoadedRorConfig.FileConfig(config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, indexConfigManager, esConfigPath, rorConfigurationIndex, mocksProvider)
        case LoadedRorConfig.ForcedFileConfig(config) =>
          RorInstance.createWithoutPeriodicIndexCheck(this, engine, config, indexConfigManager, esConfigPath, rorConfigurationIndex, mocksProvider)
        case LoadedRorConfig.IndexConfig(_, config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, indexConfigManager, esConfigPath, rorConfigurationIndex, mocksProvider)
      }
    }
  }

  private[ror] def loadRorCore(config: RawRorConfig,
                               rorIndexNameConfiguration: RorConfigurationIndex,
                               mocksProvider: MocksProvider): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider
    coreFactory
      .createCoreFrom(config, rorIndexNameConfiguration, httpClientsFactory, ldapConnectionPoolProvider, mocksProvider)
      .map { result =>
        result
          .right
          .map { coreSettings =>
            implicit val loggingContext: LoggingContext =
              LoggingContext(coreSettings.aclEngine.staticContext.obfuscatedHeaders)
            val engine = new Engine(
              accessControl = new AccessControlLoggingDecorator(
                underlying = coreSettings.aclEngine,
                auditingTool = createAuditingTool(coreSettings)
              ),
              httpClientsFactory = httpClientsFactory,
              ldapConnectionPoolProvider
            )
            engine.accessControl.staticContext.usedFlsEngineInFieldsRule.foreach {
              case FlsEngine.Lucene | FlsEngine.ESWithLucene =>
                logger.warn("Defined fls engine relies on lucene. To make it work well, all nodes should have ROR plugin installed.")
              case FlsEngine.ES =>
                logger.warn("Defined fls engine relies on ES only. This engine doesn't provide full FLS functionality hence some requests may be rejected.")
            }
            engine
          }
          .left
          .map { errors =>
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
  }

  private def createAuditingTool(coreSettings: CoreSettings)
                                (implicit loggingContext: LoggingContext): Option[AuditingTool] = {
    coreSettings.auditingSettings
      .map(settings => new AuditingTool(settings, createAuditSink(settings)))
  }

  private def createAuditSink(auditSettings: AuditingTool.Settings) = {
    auditSettings.customAuditCluster match {
      case Some(definedCustomCluster) =>
        auditSinkCreators.customCluster(definedCustomCluster)
      case None =>
        auditSinkCreators.default()
    }
  }
}

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          initialEngine: (Engine, RawRorConfig),
                          reloadInProgress: Semaphore[Task],
                          indexConfigManager: IndexConfigManager,
                          esConfigPath: Path,
                          rorConfigurationIndex: RorConfigurationIndex,
                          mocksProvider: MutableMocksProviderWithCachePerRequest)
                         (implicit propertiesProvider: PropertiesProvider,
                          scheduler: Scheduler)
  extends Logging {

  import RorInstance.ScheduledReloadError.{EngineReloadError, ReloadingInProgress}
  import RorInstance._

  logger.info("ReadonlyREST core was loaded ...")
  mode match {
    case Mode.WithPeriodicIndexCheck =>
      RorProperties.rorIndexSettingReloadInterval match {
        case RefreshInterval.Disabled =>
          logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling in-index settings check disabled")
        case RefreshInterval.Enabled(interval) =>
          scheduleIndexConfigChecking(interval)
      }
    case Mode.NoPeriodicIndexCheck => Cancelable.empty
  }

  private val aMainEngine = new MainReloadableEngine(
    boot,
    initialEngine,
    reloadInProgress,
    indexConfigManager,
    rorConfigurationIndex,
    mocksProvider
  )
  private val anImpersonatorsEngine = new ImpersonatorsReloadableEngine(
    boot,
    reloadInProgress,
    rorConfigurationIndex,
    mocksProvider
  )

  private val configRestApi = new ConfigApi(
    rorInstance = this,
    indexConfigManager,
    new FileConfigLoader(esConfigPath, propertiesProvider),
    rorConfigurationIndex
  )

  private val authMockRestApi = new AuthMockApi(
    mocksProvider
  )

  def engines: Option[Engines] = aMainEngine.engine.map(Engines(_, anImpersonatorsEngine.engine))

  def configApi: ConfigApi = configRestApi

  def authMockApi: AuthMockApi = authMockRestApi

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, Unit]] =
    aMainEngine.forceReloadFromIndex()

  def forceReloadAndSave(config: RawRorConfig)
                        (implicit requestId: RequestId): Task[Either[IndexConfigReloadWithUpdateError, Unit]] =
    aMainEngine.forceReloadAndSave(config)

  def forceReloadImpersonatorsEngine(config: RawRorConfig,
                                     ttl: FiniteDuration)
                                    (implicit requestId: RequestId): Task[Either[RawConfigReloadError, Unit]] = {
    anImpersonatorsEngine.forceReloadImpersonatorsEngine(config, ttl)
  }

  def invalidateImpersonationEngine()
                                   (implicit requestId: RequestId): Task[Unit] = {
    anImpersonatorsEngine.invalidateImpersonationEngine()
  }

  def stop(): Task[Unit] = {
    implicit val requestId: RequestId = RequestId("ES sigterm")
    for {
      _ <- anImpersonatorsEngine.stop()
      _ <- aMainEngine.stop()
    } yield ()
  }

  private def scheduleIndexConfigChecking(interval: FiniteDuration Refined Positive): Cancelable = {
    logger.debug(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index settings check within $interval")
    scheduler.scheduleOnce(interval.value) {
      implicit val requestId: RequestId = RequestId(JavaUuidProvider.random.toString)
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ReadonlyREST config from index ...")
      tryEngineReload()
        .runAsync {
          case Right(Right(_)) =>
            scheduleIndexConfigChecking(interval)
          case Right(Left(ReloadingInProgress)) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Reloading in progress ... skipping")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate(_))))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Settings are up to date. Nothing to reload.")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Stopping periodic settings check - application is being stopped")
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(startingFailure))))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ReadonlyREST starting failed: ${startingFailure.message}")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.LoadingConfigError(error)))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading config from index failed: ${error.show}")
            scheduleIndexConfigChecking(interval)
          case Left(ex) =>
            logger.error(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Checking index settings failed: error", ex)
            scheduleIndexConfigChecking(interval)
        }
    }
  }

  private def tryEngineReload()
                             (implicit requestId: RequestId) = {
    val criticalSection = Resource.make(reloadInProgress.tryAcquire) {
      case true =>
        reloadInProgress.release
      case false =>
        Task.unit
    }
    criticalSection.use {
      case true =>
        aMainEngine
          .reloadEngineUsingIndexConfigWithoutPermit()
          .map(_.leftMap(ScheduledReloadError.EngineReloadError.apply))
      case false =>
        Task.now(Left(ScheduledReloadError.ReloadingInProgress))
    }
  }

}

object RorInstance {

  sealed trait RawConfigReloadError
  object RawConfigReloadError {
    final case class ReloadingFailed(failure: StartingFailure) extends RawConfigReloadError
    final case class ConfigUpToDate(config: RawRorConfig) extends RawConfigReloadError
    object RorInstanceStopped extends RawConfigReloadError
  }

  sealed trait IndexConfigReloadWithUpdateError
  object IndexConfigReloadWithUpdateError {
    final case class ReloadError(undefined: RawConfigReloadError) extends IndexConfigReloadWithUpdateError
    final case class IndexConfigSavingError(underlying: SavingIndexConfigError) extends IndexConfigReloadWithUpdateError
  }

  sealed trait IndexConfigReloadError
  object IndexConfigReloadError {
    final case class LoadingConfigError(underlying: ConfigLoaderError[IndexConfigManager.IndexConfigError]) extends IndexConfigReloadError
    final case class ReloadError(underlying: RawConfigReloadError) extends IndexConfigReloadError
  }

  private sealed trait ScheduledReloadError
  private object ScheduledReloadError {
    case object ReloadingInProgress extends ScheduledReloadError
    final case class EngineReloadError(underlying: IndexConfigReloadError) extends ScheduledReloadError
  }

  def createWithPeriodicIndexCheck(boot: ReadonlyRest,
                                   engine: Engine,
                                   config: RawRorConfig,
                                   indexConfigManager: IndexConfigManager,
                                   esConfigPath: Path,
                                   rorConfigurationIndex: RorConfigurationIndex,
                                   mocksProvider: MutableMocksProviderWithCachePerRequest)
                                  (implicit propertiesProvider: PropertiesProvider,
                                   scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.WithPeriodicIndexCheck, engine, config, indexConfigManager, esConfigPath, rorConfigurationIndex, mocksProvider)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      engine: Engine,
                                      config: RawRorConfig,
                                      indexConfigManager: IndexConfigManager,
                                      esConfigPath: Path,
                                      rorConfigurationIndex: RorConfigurationIndex,
                                      mocksProvider: MutableMocksProviderWithCachePerRequest)
                                     (implicit propertiesProvider: PropertiesProvider,
                                      scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.NoPeriodicIndexCheck, engine, config, indexConfigManager, esConfigPath, rorConfigurationIndex, mocksProvider)
  }

  private def create(boot: ReadonlyRest,
                     mode: RorInstance.Mode,
                     engine: Engine,
                     config: RawRorConfig,
                     indexConfigManager: IndexConfigManager,
                     esConfigPath: Path,
                     rorConfigurationIndex: RorConfigurationIndex,
                     mocksProvider: MutableMocksProviderWithCachePerRequest)
                    (implicit propertiesProvider: PropertiesProvider,
                     scheduler: Scheduler) = {
    Semaphore[Task](1)
      .map { isReloadInProgressSemaphore =>
        new RorInstance(
          boot,
          mode,
          (engine, config),
          isReloadInProgressSemaphore,
          indexConfigManager,
          esConfigPath,
          rorConfigurationIndex,
          mocksProvider
        )
      }
  }

  private sealed trait Mode
  private object Mode {
    case object WithPeriodicIndexCheck extends Mode
    case object NoPeriodicIndexCheck extends Mode
  }
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

final case class AuditSinkCreators(default: () => AuditSinkService,
                                   customCluster: AuditCluster => AuditSinkService)

sealed trait RorMode

object RorMode {
  case object Plugin extends RorMode
  case object Proxy extends RorMode
}

final class Engine(val accessControl: AccessControl,
                   httpClientsFactory: AsyncHttpClientsFactory,
                   ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider)
                  (implicit scheduler: Scheduler) {

  private[ror] def shutdown(): Unit = {
    httpClientsFactory.shutdown()
    ldapConnectionPoolProvider.close().runAsyncAndForget
  }
}
