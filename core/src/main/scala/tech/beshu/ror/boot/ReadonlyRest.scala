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
import tech.beshu.ror.accesscontrol.blocks.mocks.{MutableMocksProviderWithCachePerRequest, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, CoreFactory, CoreSettings, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.{AccessControlLoggingDecorator, AuditingTool, LoggingContext}
import tech.beshu.ror.api.{AuthMockApi, ConfigApi}
import tech.beshu.ror.boot.ReadonlyRest.AuditSinkCreator
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

import java.nio.file.Path
import java.time.Clock
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

class ReadonlyRest(coreFactory: CoreFactory,
                   auditSinkCreator: AuditSinkCreator,
                   val indexConfigManager: IndexConfigManager,
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
      instance <- startRor(esConfig, loadedRorConfig)
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

  private def startRor(esConfig: EsConfig,
                       loadedConfig: LoadedRorConfig[RawRorConfig]) = {
    for {
      engine <- EitherT(loadRorCore(loadedConfig.value, esConfig.rorIndex.index))
      rorInstance <- createRorInstance(esConfig.rorIndex.index, engine, loadedConfig)
    } yield rorInstance
  }

  private def createRorInstance(rorConfigurationIndex: RorConfigurationIndex,
                                engine: Engine,
                                loadedConfig: LoadedRorConfig[RawRorConfig]) = {
    EitherT.right[StartingFailure] {
      loadedConfig match {
        case LoadedRorConfig.FileConfig(config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, rorConfigurationIndex)
        case LoadedRorConfig.ForcedFileConfig(config) =>
          RorInstance.createWithoutPeriodicIndexCheck(this, engine, config, rorConfigurationIndex)
        case LoadedRorConfig.IndexConfig(_, config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, rorConfigurationIndex)
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
      .map(settings => new AuditingTool(settings, auditSinkCreator(settings.auditCluster)))
  }
}

object ReadonlyRest {
  type AuditSinkCreator = AuditCluster => AuditSinkService

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
    val mocksProvider = new MutableMocksProviderWithCachePerRequest(NoOpMocksProvider)

    new ReadonlyRest(coreFactory, auditSinkCreator, indexConfigManager, mocksProvider, esConfigPath)
  }
}

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          initialEngine: (Engine, RawRorConfig),
                          reloadInProgress: Semaphore[Task],
                          rorConfigurationIndex: RorConfigurationIndex)
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
    rorConfigurationIndex,
  )
  private val anImpersonatorsEngine = new ImpersonatorsReloadableEngine(
    boot,
    reloadInProgress,
    rorConfigurationIndex
  )

  private val configRestApi = new ConfigApi(
    rorInstance = this,
    boot.indexConfigManager,
    new FileConfigLoader(boot.esConfigPath),
    rorConfigurationIndex
  )

  private val authMockRestApi = new AuthMockApi(boot.mocksProvider)

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
                                   rorConfigurationIndex: RorConfigurationIndex)
                                  (implicit propertiesProvider: PropertiesProvider,
                                   scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.WithPeriodicIndexCheck, engine, config, rorConfigurationIndex)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      engine: Engine,
                                      config: RawRorConfig,
                                      rorConfigurationIndex: RorConfigurationIndex)
                                     (implicit propertiesProvider: PropertiesProvider,
                                      scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.NoPeriodicIndexCheck, engine, config, rorConfigurationIndex)
  }

  private def create(boot: ReadonlyRest,
                     mode: RorInstance.Mode,
                     engine: Engine,
                     config: RawRorConfig,
                     rorConfigurationIndex: RorConfigurationIndex)
                    (implicit propertiesProvider: PropertiesProvider,
                     scheduler: Scheduler) = {
    Semaphore[Task](1)
      .map { isReloadInProgressSemaphore =>
        new RorInstance(
          boot,
          mode,
          (engine, config),
          isReloadInProgressSemaphore,
          rorConfigurationIndex
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
