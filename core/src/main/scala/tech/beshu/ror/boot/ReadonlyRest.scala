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
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, CoreFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.{AccessControlLoggingDecorator, AuditingTool, LoggingContext}
import tech.beshu.ror.boot.engines.{Engines, ImpersonatorsReloadableEngine, MainReloadableEngine}
import tech.beshu.ror.configuration.ConfigLoading.{ErrorOr, LoadRorConfig}
import tech.beshu.ror.configuration.IndexConfigManager.SavingIndexConfigError
import tech.beshu.ror.configuration.RorProperties.RefreshInterval
import tech.beshu.ror.configuration._
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError._
import tech.beshu.ror.configuration.loader.{ConfigLoadingInterpreter, LoadRawRorConfig, LoadedRorConfig}
import tech.beshu.ror.es.{AuditSinkService, IndexJsonContentService}
import tech.beshu.ror.providers._

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

class Ror(mode: RorMode,
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

  protected implicit def scheduler: Scheduler

  protected implicit def envVarsProvider: EnvVarsProvider

  protected implicit def propertiesProvider: PropertiesProvider

  protected implicit def clock: Clock

  def start(esConfigPath: Path,
            auditSink: AuditSinkService,
            indexContentService: IndexJsonContentService)
           (implicit envVarsProvider: EnvVarsProvider): Task[Either[StartingFailure, RorInstance]] = {
    val indexConfigManager = new IndexConfigManager(indexContentService)
    (for {
      esConfig <- loadEsConfig(esConfigPath, indexConfigManager)
      loadedRorConfig <- loadRorConfig(esConfigPath, esConfig, indexConfigManager)
      instance <- startRor(esConfig, loadedRorConfig, indexConfigManager, auditSink)
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

  private def startRor(esConfig: EsConfig,
                       loadedConfig: LoadedRorConfig[RawRorConfig],
                       indexConfigManager: IndexConfigManager,
                       auditSink: AuditSinkService) = {
    for {
      engine <- EitherT(loadRorCore(loadedConfig.value, esConfig.rorIndex.index, auditSink))
      rorInstance <- createRorInstance(indexConfigManager, esConfig.rorIndex.index, auditSink, engine, loadedConfig)
    } yield rorInstance
  }

  private def createRorInstance(indexConfigManager: IndexConfigManager,
                                rorConfigurationIndex: RorConfigurationIndex,
                                auditSink: AuditSinkService,
                                engine: Engine,
                                loadedConfig: LoadedRorConfig[RawRorConfig]) = {
    EitherT.right[StartingFailure] {
      loadedConfig match {
        case LoadedRorConfig.FileConfig(config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, indexConfigManager, rorConfigurationIndex, auditSink)
        case LoadedRorConfig.ForcedFileConfig(config) =>
          RorInstance.createWithoutPeriodicIndexCheck(this, engine, config, indexConfigManager, rorConfigurationIndex, auditSink)
        case LoadedRorConfig.IndexConfig(_, config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, indexConfigManager, rorConfigurationIndex, auditSink)
      }
    }
  }

  private[ror] def loadRorCore(config: RawRorConfig,
                               rorIndexNameConfiguration: RorConfigurationIndex,
                               auditSink: AuditSinkService): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider
    coreFactory
      .createCoreFrom(config, rorIndexNameConfiguration, httpClientsFactory, ldapConnectionPoolProvider)
      .map { result =>
        result
          .right
          .map { coreSettings =>
            implicit val loggingContext: LoggingContext =
              LoggingContext(coreSettings.aclEngine.staticContext.obfuscatedHeaders)
            val engine = new Engine(
              accessControl = new AccessControlLoggingDecorator(
                underlying = coreSettings.aclEngine,
                auditingTool = coreSettings.auditingSettings.map(new AuditingTool(_, auditSink))
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

}

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          initialEngine: (Engine, RawRorConfig),
                          reloadInProgress: Semaphore[Task],
                          indexConfigManager: IndexConfigManager,
                          rorConfigurationIndex: RorConfigurationIndex,
                          auditSink: AuditSinkService)
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
    auditSink
  )
  private val anImpersonatorsEngine = new ImpersonatorsReloadableEngine(
    boot,
    reloadInProgress,
    rorConfigurationIndex,
    auditSink
  )

  // todo: remove
  def mainEngine: Option[Engine] = aMainEngine.engine

  def engines: Option[Engines] = aMainEngine.engine.map(Engines(_, anImpersonatorsEngine.engine))

  def forceReloadFromIndex(): Task[Either[IndexConfigReloadError, Unit]] =
    aMainEngine.forceReloadFromIndex()

  def forceReloadAndSave(config: RawRorConfig): Task[Either[IndexConfigReloadWithUpdateError, Unit]] =
    aMainEngine.forceReloadAndSave(config)

  def forceReloadImpersonatorsEngine(config: RawRorConfig,
                                     ttl: FiniteDuration): Task[Either[RawConfigReloadError, Unit]] = {
    anImpersonatorsEngine.forceReloadImpersonatorsEngine(config, ttl)
  }

  def invalidateImpersonationEngine(): Task[Unit] = {
    anImpersonatorsEngine.invalidateImpersonationEngine()
  }

  def stop(): Task[Unit] = for {
    _ <- anImpersonatorsEngine.stop()
    _ <- aMainEngine.stop()
  } yield ()

  private def scheduleIndexConfigChecking(interval: FiniteDuration Refined Positive): Cancelable = {
    logger.debug(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index settings check within $interval")
    scheduler.scheduleOnce(interval.value) {
      logger.debug("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST config from index ...")
      tryEngineReload()
        .runAsync {
          case Right(Right(_)) =>
            scheduleIndexConfigChecking(interval)
          case Right(Left(ReloadingInProgress)) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] Reloading in progress ... skipping")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate)))) =>
            logger.debug("[CLUSTERWIDE SETTINGS] Settings are up to date. Nothing to reload.")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)))) =>
            logger.debug("[CLUSTERWIDE SETTINGS] Stopping periodic settings check - application is being stopped")
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(startingFailure))))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] ReadonlyREST starting failed: ${startingFailure.message}")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.LoadingConfigError(error)))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] Loading config from index failed: ${error.show}")
            scheduleIndexConfigChecking(interval)
          case Left(ex) =>
            logger.error("[CLUSTERWIDE SETTINGS] Checking index settings failed: error", ex)
            scheduleIndexConfigChecking(interval)
        }
    }
  }

  private def tryEngineReload() = {
    val criticalSection = Resource.make(reloadInProgress.tryAcquire) {
      case true => reloadInProgress.release
      case false => Task.unit
    }
    criticalSection.use {
      case true =>
        aMainEngine
          .reloadEngineUsingIndexConfig()
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
    object ConfigUpToDate extends RawConfigReloadError
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
                                   rorConfigurationIndex: RorConfigurationIndex,
                                   auditSink: AuditSinkService)
                                  (implicit propertiesProvider: PropertiesProvider,
                                   scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.WithPeriodicIndexCheck, engine, config, indexConfigManager, rorConfigurationIndex, auditSink)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      engine: Engine,
                                      config: RawRorConfig,
                                      indexConfigManager: IndexConfigManager,
                                      rorConfigurationIndex: RorConfigurationIndex,
                                      auditSink: AuditSinkService)
                                     (implicit propertiesProvider: PropertiesProvider,
                                      scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.NoPeriodicIndexCheck, engine, config, indexConfigManager, rorConfigurationIndex, auditSink)
  }

  private def create(boot: ReadonlyRest,
                     mode: RorInstance.Mode,
                     engine: Engine,
                     config: RawRorConfig,
                     indexConfigManager: IndexConfigManager,
                     rorConfigurationIndex: RorConfigurationIndex,
                     auditSink: AuditSinkService)
                    (implicit propertiesProvider: PropertiesProvider,
                     scheduler: Scheduler) = {
    Semaphore[Task](1)
      .map { isReloadInProgressSemaphore =>
        new RorInstance(boot, mode, (engine, config), isReloadInProgressSemaphore, indexConfigManager, rorConfigurationIndex, auditSink)
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
