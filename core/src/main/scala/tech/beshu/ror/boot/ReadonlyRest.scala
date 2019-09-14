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
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.Scheduler.{global => scheduler}
import monix.execution.atomic.{Atomic, AtomicAny}
import monix.execution.{Cancelable, CancelablePromise, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, CoreFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.{AccessControlLoggingDecorator, AuditingTool, LoggingContext, LoggingContextFactory}
import tech.beshu.ror.accesscontrol.{AccessControl, AccessControlStaticContext}
import tech.beshu.ror.boot.RorInstance.{ForceReloadError, Mode}
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError._
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError._
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError.{IndexConfigNotExist, IndexConfigUnknownStructure}
import tech.beshu.ror.configuration.{EsConfig, FileConfigLoader, IndexConfigManager, RawRorConfig}
import tech.beshu.ror.es.{AuditSink, IndexJsonContentManager}
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.TaskOps._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

object Ror extends ReadonlyRest {

  val blockingScheduler: Scheduler = Scheduler.io("blocking-index-content-provider")

  override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  override protected implicit val clock: Clock = Clock.systemUTC()

  override protected val coreFactory: CoreFactory = {
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
    implicit val _envVarsProvider: EnvVarsProvider = envVarsProvider
    new RawRorConfigBasedCoreFactory
  }
}

trait ReadonlyRest extends Logging {

  protected def envVarsProvider: EnvVarsProvider
  protected implicit def clock: Clock
  protected def coreFactory: CoreFactory

  def start(esConfigPath: Path,
            auditSink: AuditSink,
            indexContentManager: IndexJsonContentManager): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      fileConfigLoader <- createFileConfigLoader(esConfigPath)
      indexConfigLoader <- createIndexConfigLoader(indexContentManager)
      esConfig <- loadEsConfig(esConfigPath)
      instance <- startRor(esConfig, fileConfigLoader, indexConfigLoader, auditSink)
    } yield instance).value
  }

  private def createFileConfigLoader(esConfigPath: Path) = {
    EitherT.pure[Task, StartingFailure](FileConfigLoader.create(esConfigPath))
  }

  private def createIndexConfigLoader(indexContentManager: IndexJsonContentManager) = {
    EitherT.pure[Task, StartingFailure](new IndexConfigManager(indexContentManager))
  }

  private def loadEsConfig(esConfigPath: Path) = {
    EitherT {
      EsConfig
        .from(esConfigPath)
        .map(_.left.map {
          case LoadEsConfigError.FileNotFound(file) =>
            StartingFailure(s"Cannot find elasticsearch settings file: [${file.pathAsString}]")
          case LoadEsConfigError.MalformedContent(file, msg) =>
            StartingFailure(s"Settings file is malformed: [${file.pathAsString}], $msg")
        })
    }
  }

  private def startRor(esConfig: EsConfig,
                       fileConfigLoader: FileConfigLoader,
                       indexConfigManager: IndexConfigManager,
                       auditSink: AuditSink) = {
    if (esConfig.rorEsLevelSettings.forceLoadRorFromFile) {
      for {
        config <- EitherT(loadRorConfigFromFile(fileConfigLoader))
        engine <- EitherT(loadRorCore(config, auditSink))
      } yield RorInstance.createWithoutPeriodicIndexCheck(this, engine, config, indexConfigManager, auditSink)
    } else {
      for {
        config <- EitherT(loadRorConfigFromIndex(indexConfigManager, loadRorConfigFromFile(fileConfigLoader)))
        engine <- EitherT(loadRorCore(config, auditSink))
      } yield RorInstance.createWithPeriodicIndexCheck(this, engine, config, indexConfigManager, auditSink)
    }
  }

  private def loadRorConfigFromFile(fileConfigLoader: FileConfigLoader) = {
    logger.info(s"Loading ReadonlyREST settings from file: ${fileConfigLoader.rawConfigFile.pathAsString}")
    fileConfigLoader
      .load()
      .map {
        case Right(config) =>
          Right(config)
        case Left(error@ParsingError(_)) =>
          Left(StartingFailure(ConfigLoaderError.show[FileConfigError].show(error)))
        case Left(error@SpecializedError(_)) =>
          Left(StartingFailure(ConfigLoaderError.show[FileConfigError].show(error)))
      }
      .andThen {
        case Success(Left(error)) =>
          logger.error(s"Loading ReadonlyREST from file failed: ${error.message}")
      }
  }

  private[ror] def loadRorConfigFromIndex(indexConfigManager: IndexConfigManager,
                                          noIndexFallback: => Task[Either[StartingFailure, RawRorConfig]]) = {
    logger.info("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index ...")
    indexConfigManager
      .load()
      .flatMap {
        case Right(config) =>
          Task.now(Right(config))
        case Left(error@ParsingError(_)) =>
          val failure = StartingFailure(ConfigLoaderError.show[IndexConfigError].show(error))
          logger.error(s"Loading ReadonlyREST config from index failed: ${failure.message}")
          lift(failure)
        case Left(SpecializedError(IndexConfigNotExist)) =>
          logger.info(s"Loading ReadonlyREST config from index failed: cannot find index")
          noIndexFallback
        case Left(SpecializedError(IndexConfigUnknownStructure)) =>
          logger.info(s"Loading ReadonlyREST config from index failed: index content malformed")
          noIndexFallback
      }
  }

  private[ror] def loadRorCore(config: RawRorConfig, auditSink: AuditSink): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    coreFactory
      .createCoreFrom(config, httpClientsFactory)
      .map { result =>
        result
          .right
          .map { coreSettings =>
            implicit val loggingContext = LoggingContextFactory.create(coreSettings.aclStaticContext.obfuscatedHeaders)
            val engine = new Engine(accessControl = new AccessControlLoggingDecorator(
                            coreSettings.aclEngine,
                            coreSettings.auditingSettings.map(new AuditingTool(_, auditSink))
                          ), context = coreSettings.aclStaticContext, httpClientsFactory = httpClientsFactory)
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

  private def lift(sf: StartingFailure) = Task.now(Left(sf))
}

class RorInstance private(boot: ReadonlyRest,
                          mode: Mode,
                          initialEngine: (Engine, RawRorConfig),
                          indexConfigManager: IndexConfigManager,
                          auditSink: AuditSink)
  extends Logging {

  logger.info("Readonly REST plugin core was loaded ...")
  private val instanceState: Atomic[State] =
    AtomicAny(State.EngineLoaded(
      State.EngineLoaded.EngineWithConfig(initialEngine._1, initialEngine._2),
      mode match {
        case Mode.WithPeriodicIndexCheck => scheduleIndexConfigChecking()
        case Mode.NoPeriodicIndexCheck => Cancelable.empty
      }
    ))

  def engine: Option[Engine] = instanceState.get() match {
    case State.Initiated(_) => None
    case State.EngineLoaded(State.EngineLoaded.EngineWithConfig(engine, _), _) => Some(engine)
    case State.Stopped => None
  }

  def forceReloadFromIndex(): Task[Either[ForceReloadError, Unit]] = {
    val promise = CancelablePromise[Either[ForceReloadError, Unit]]()
    tryReloadingEngine()
      .runAsync {
        case Right(Right(Some(state))) =>
          state match {
            case State.Initiated(_) =>
              logger.error(s"[CLUSTERWIDE SETTINGS] Unexpected state: Initialized")
              promise.success(Left(ForceReloadError.ReloadingError))
            case State.EngineLoaded(_, _) =>
              promise.success(Right(()))
            case State.Stopped =>
              promise.success(Left(ForceReloadError.StoppedInstance))
          }
        case Right(Right(None)) =>
          logger.debug("[CLUSTERWIDE SETTINGS] Index settings is the same as loaded one. Nothing to do.")
          promise.success(Left(ForceReloadError.ConfigUpToDate))
        case Right(Left(startingFailure)) =>
          logger.debug(s"[CLUSTERWIDE SETTINGS] ROR configuration starting failed: ${startingFailure.message}")
          promise.success(Left(ForceReloadError.CannotReload(startingFailure)))
        case Left(ex) =>
          logger.errorEx("[CLUSTERWIDE SETTINGS] Force reloading failed", ex)
          promise.success(Left(ForceReloadError.ReloadingError))
      }
    Task.fromCancelablePromise(promise)
  }

  def stop(): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        State.Stopped
      case State.EngineLoaded(State.EngineLoaded.EngineWithConfig(engine, _), _) =>
        engine.shutdown()
        State.Stopped
      case State.Stopped =>
        State.Stopped
    }
  }

  private def scheduleIndexConfigChecking(): Cancelable = {
    logger.debug(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index settings check within ${RorInstance.indexConfigCheckingSchedulerDelay}")
    scheduler.scheduleOnce(RorInstance.indexConfigCheckingSchedulerDelay) {
      logger.debug("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST config from index ...")
      tryReloadingEngine()
        .runAsync {
          case Right(Right(Some(_))) =>
          case Right(Right(None)) =>
            logger.debug("[CLUSTERWIDE SETTINGS] Settings are up to date. Nothing to reload.")
            scheduleNewConfigCheck()
          case Right(Left(startingFailure)) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] ReadonlyREST starting failed: ${startingFailure.message}")
            scheduleNewConfigCheck()
          case Left(ex) =>
            logger.error("[CLUSTERWIDE SETTINGS] Checking index settings failed: error", ex)
            scheduleNewConfigCheck()
        }
    }
  }

  private def tryReloadingEngine() = {
    loadNewEngineFromIndex()
      .flatMap {
        case Right(Some(newEngine)) =>
          logger.info("ReadonlyREST new configuration found ...")
          applyNewEngine(newEngine).map(Some.apply).map(Right.apply)
        case Right(None) => Task.now(Right(None))
        case Left(failure) => Task.now(Left(failure))
      }
  }

  private def applyNewEngine(newEngine: State.EngineLoaded.EngineWithConfig) = {
    val promise = CancelablePromise[State]()
    instanceState.transform {
      case State.Initiated(cancelable) =>
        cancelable.cancel()
        val newState = State.EngineLoaded(newEngine, scheduleIndexConfigChecking())
        promise.success(newState)
        logger.info("ReadonlyREST plugin core was reloaded ...")
        newState
      case State.EngineLoaded(State.EngineLoaded.EngineWithConfig(oldEngine, _), _) =>
        scheduleDelayedShutdown(oldEngine)
        val newState = State.EngineLoaded(newEngine, scheduleIndexConfigChecking())
        promise.success(newState)
        logger.info("ReadonlyREST plugin core was reloaded ...")
        newState
      case State.Stopped =>
        newEngine.engine.shutdown()
        val newState = State.Stopped
        promise.success(newState)
        logger.error("Cannot load new ReadonlyREST core, because its instance was stopped")
        newState
    }
    Task.fromCancelablePromise(promise)
  }

  private def scheduleDelayedShutdown(engine: Engine) = {
    scheduler.scheduleOnce(RorInstance.delayOfOldEngineShutdown) {
      engine.shutdown()
    }
  }

  private def loadNewEngineFromIndex(): Task[Either[StartingFailure, Option[State.EngineLoaded.EngineWithConfig]]] = {
    indexConfigManager
      .load()
      .flatMap {
        case Right(config) =>
          shouldBeReloaded(config)
            .flatMap {
              case true =>
                boot
                  .loadRorCore(config, auditSink)
                  .map(_.map(engine => Some(State.EngineLoaded.EngineWithConfig(engine, config))))
              case false =>
                Task.now(Right(Option.empty[State.EngineLoaded.EngineWithConfig]))
            }
        case Left(error) =>
          val failure = StartingFailure(ConfigLoaderError.show[IndexConfigError].show(error))
          Task.now(Left(failure))
      }
  }

  private def scheduleNewConfigCheck(): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        State.Initiated(scheduleIndexConfigChecking())
      case State.EngineLoaded(State.EngineLoaded.EngineWithConfig(engine, config), _) =>
        State.EngineLoaded(State.EngineLoaded.EngineWithConfig(engine, config), scheduleIndexConfigChecking())
      case State.Stopped =>
        State.Stopped
    }
  }

  private def shouldBeReloaded(newConfig: RawRorConfig) = {
    val promise = CancelablePromise[Boolean]()
    instanceState.transform {
      case state@State.Initiated(_) =>
        promise.success(true)
        state
      case state@State.EngineLoaded(State.EngineLoaded.EngineWithConfig(_, oldConfig), _) =>
        promise.success(newConfig != oldConfig)
        state
      case state@State.Stopped =>
        promise.success(false)
        state
    }
    Task.fromCancelablePromise(promise)
  }

  private[this] sealed trait State
  private object State {
    sealed case class Initiated(scheduledInitLoadingJob: Cancelable) extends State
    sealed case class EngineLoaded(engine: EngineLoaded.EngineWithConfig, scheduledInitLoadingJob: Cancelable) extends State
    object EngineLoaded {
      sealed case class EngineWithConfig(engine: Engine, config: RawRorConfig)
    }
    case object Stopped extends State

  }
}

object RorInstance {

  def createWithPeriodicIndexCheck(boot: ReadonlyRest,
                                   engine: Engine,
                                   config: RawRorConfig,
                                   indexConfigManager: IndexConfigManager,
                                   auditSink: AuditSink): RorInstance = {
    new RorInstance(boot, Mode.WithPeriodicIndexCheck, (engine, config), indexConfigManager, auditSink)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      engine: Engine,
                                      config: RawRorConfig,
                                      indexConfigManager: IndexConfigManager,
                                      auditSink: AuditSink): RorInstance = {
    new RorInstance(boot, Mode.NoPeriodicIndexCheck, (engine, config), indexConfigManager, auditSink)
  }

  private sealed trait Mode
  private object Mode {
    case object WithPeriodicIndexCheck extends Mode
    case object NoPeriodicIndexCheck extends Mode
  }

  private val indexConfigCheckingSchedulerDelay = 5 second
  private val delayOfOldEngineShutdown = 10 seconds

  sealed trait ForceReloadError
  object ForceReloadError {
    final case class CannotReload(startingFailure: StartingFailure) extends ForceReloadError
    case object ReloadingError extends ForceReloadError
    case object StoppedInstance extends ForceReloadError
    case object ConfigUpToDate extends ForceReloadError
  }
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

final class Engine(val accessControl: AccessControl, val context: AccessControlStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
  private[ror] def shutdown(): Unit = {
    httpClientsFactory.shutdown()
  }
}


