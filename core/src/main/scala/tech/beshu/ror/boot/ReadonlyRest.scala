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
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, RawRorConfigBasedCoreFactory, CoreFactory}
import tech.beshu.ror.acl.logging.{AclLoggingDecorator, AuditingTool}
import tech.beshu.ror.acl.utils.StaticVariablesResolver
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.boot.RorInstance.{ForceReloadError, noIndexStartingFailure}
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError._
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError._
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError.{IndexConfigNotExist, IndexConfigUnknownStructure}
import tech.beshu.ror.configuration.{EsConfig, FileConfigLoader, IndexConfigManager, RawRorConfig}
import tech.beshu.ror.es.{AuditSink, IndexJsonContentManager}
import tech.beshu.ror.utils.{EnvVarsProvider, JavaUuidProvider, OsEnvVarsProvider, UuidProvider}
import tech.beshu.ror.utils.LoggerOps._
import scala.concurrent.duration._
import scala.language.postfixOps

object Ror extends ReadonlyRest {

  val blockingScheduler: Scheduler= Scheduler.io("blocking-index-content-provider")

  override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  override protected implicit val clock: Clock = Clock.systemUTC()

  override protected val coreFactory: CoreFactory = {
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(envVarsProvider)
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
    EitherT.pure[Task, StartingFailure](new FileConfigLoader(esConfigPath, envVarsProvider))
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
            StartingFailure(s"Cannot find elasticsearch config file: [${file.pathAsString}]")
          case LoadEsConfigError.MalformedContent(file, msg) =>
            StartingFailure(s"Elasticsearch config file is malformed: [${file.pathAsString}], $msg")
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
      } yield new RorInstance(this, engine, config, indexConfigManager, auditSink)
    } else {
      EitherT.pure[Task, StartingFailure](
        new RorInstance(
          this,
          indexConfigManager,
          auditSink,
          loadRorConfigFromFile(fileConfigLoader)
        )
      )
    }
  }

  private def loadRorConfigFromFile(fileConfigLoader: FileConfigLoader) = {
    logger.info(s"Loading ReadonlyREST config from file: ${fileConfigLoader.rawConfigFile.pathAsString}")
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
  }

  private[ror] def loadRorConfigFromIndex(indexConfigManager: IndexConfigManager,
                                          noIndexFallback: => Task[Either[StartingFailure, RawRorConfig]]) = {
    logger.info("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST config from index ...")
    indexConfigManager
      .load()
      .flatMap {
        case Right(config) =>
          Task.now(Right(config))
        case Left(error@ParsingError(_)) =>
          lift(StartingFailure(ConfigLoaderError.show[IndexConfigError].show(error)))
        case Left(SpecializedError(IndexConfigNotExist)) =>
          noIndexFallback
        case Left(SpecializedError(IndexConfigUnknownStructure)) =>
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
            val engine = new Engine(
              new AclLoggingDecorator(
                coreSettings.aclEngine,
                coreSettings.auditingSettings.map(new AuditingTool(_, auditSink))
              ),
              coreSettings.aclStaticContext,
              httpClientsFactory
            )
            engine
          }
          .left
          .map { errors =>
            val errorsMessage = errors
              .map(_.reason)
              .map {
                case Reason.Message(msg) => msg
                case Reason.MalformedValue(yamlString) => s"Malformed config: $yamlString"
              }
              .toList
              .mkString("Errors:\n", "\n", "")
            StartingFailure(errorsMessage)
          }
      }
  }

  private def lift(sf: StartingFailure) = Task.now(Left(sf))
}

class RorInstance private (boot: ReadonlyRest,
                           initialEngine: Option[(Engine, RawRorConfig)],
                           indexConfigManager: IndexConfigManager,
                           auditSink: AuditSink,
                           initialNoIndexFallback: Task[Either[StartingFailure, RawRorConfig]])
  extends Logging {

  def this(boot: ReadonlyRest,
           indexConfigManager: IndexConfigManager,
           auditSink: AuditSink,
           initialNoIndexFallback: Task[Either[StartingFailure, RawRorConfig]]) = {
    this(boot, None, indexConfigManager, auditSink, initialNoIndexFallback)
  }

  def this(boot: ReadonlyRest,
           engine: Engine,
           config: RawRorConfig,
           indexConfigManager: IndexConfigManager,
           auditSink: AuditSink) = {
    this(boot, Some((engine, config)), indexConfigManager, auditSink, noIndexStartingFailure)
  }

  private val instanceState: Atomic[State] =
    initialEngine match {
      case Some((engine, config)) =>
        logger.info("Readonly REST plugin core was loaded ...")
        AtomicAny(State.EngineLoaded(
          State.EngineLoaded.EngineWithConfig(engine, config),
          scheduleIndexConfigChecking(initialNoIndexFallback)
        ))
      case None =>
        AtomicAny(State.Initiated(scheduleIndexConfigChecking(initialNoIndexFallback)))
    }

  def engine: Option[Engine] = instanceState.get() match {
    case State.Initiated(_) => None
    case State.EngineLoaded(State.EngineLoaded.EngineWithConfig(engine, _), _) => Some(engine)
    case State.Stopped => None
  }

  def forceReloadFromIndex(): Task[Either[ForceReloadError, Unit]] = {
    val promise = CancelablePromise[Either[ForceReloadError, Unit]]()
    tryReloadingEngine(noIndexStartingFailure)
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
            logger.debug("[CLUSTERWIDE SETTINGS] Index configuration is the same as loaded one. Nothing to do.")
            promise.success(Left(ForceReloadError.ConfigUpToDate))
          case Right(Left(startingFailure)) =>
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

  private def scheduleIndexConfigChecking(noIndexFallback: Task[Either[StartingFailure, RawRorConfig]]): Cancelable = {
    logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index config check within ${RorInstance.indexConfigCheckingSchedulerDelay}")
    scheduler.scheduleOnce(RorInstance.indexConfigCheckingSchedulerDelay) {
      tryReloadingEngine(noIndexFallback)
        .runAsync {
          case Right(Right(Some(_))) =>
          case Right(Right(None)) =>
            logger.info("[CLUSTERWIDE SETTINGS] Config is up to date. Nothing to reload.")
            scheduleNewConfigCheck()
          case Right(Left(startingFailure)) =>
            logger.warn(s"[CLUSTERWIDE SETTINGS] Checking index config failed: ${startingFailure.message}")
            scheduleNewConfigCheck()
          case Left(ex) =>
            logger.warnEx("[CLUSTERWIDE SETTINGS] Checking index config failed: error", ex)
            scheduleNewConfigCheck()
        }
    }
  }

  private def tryReloadingEngine(noIndexFallback: Task[Either[StartingFailure, RawRorConfig]]) = {
    loadNewEngineFromIndex(noIndexFallback)
      .flatMap {
        case Right(Some(newEngine)) => applyNewEngine(newEngine).map(Some.apply).map(Right.apply)
        case Right(None) => Task.now(Right(None))
        case Left(failure) => Task.now(Left(failure))
      }
  }

  private def applyNewEngine(newEngine: State.EngineLoaded.EngineWithConfig) = {
    val promise = CancelablePromise[State]()
    instanceState.transform {
      case State.Initiated(cancelable) =>
        cancelable.cancel()
        val newState = State.EngineLoaded(newEngine, scheduleIndexConfigChecking(noIndexStartingFailure))
        promise.success(newState)
        logger.info("ReadonlyREST plugin core was loaded ...")
        newState
      case State.EngineLoaded(State.EngineLoaded.EngineWithConfig(oldEngine, _), _) =>
        scheduleDelayedShutdown(oldEngine)
        val newState = State.EngineLoaded(newEngine, scheduleIndexConfigChecking(noIndexStartingFailure))
        promise.success(newState)
        logger.info("ReadonlyREST plugin core was loaded ...")
        newState
      case State.Stopped =>
        newEngine.engine.shutdown()
        val newState = State.Stopped
        promise.success(newState)
        logger.error("Cannot load new ReadonlyREST core, because it's instance was stopped")
        newState
    }
    Task.fromCancelablePromise(promise)
  }

  private def scheduleDelayedShutdown(engine: Engine) = {
    scheduler.scheduleOnce(RorInstance.delayOfOldEngineShutdown) {
      engine.shutdown()
    }
  }

  private def loadNewEngineFromIndex(noIndexFallback: Task[Either[StartingFailure, RawRorConfig]]) = {
    boot
      .loadRorConfigFromIndex(indexConfigManager, noIndexFallback)
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
        case Left(failure) =>
          Task.now(Left(failure))
      }
  }

  private def scheduleNewConfigCheck(): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        State.Initiated(scheduleIndexConfigChecking(noIndexStartingFailure))
      case State.EngineLoaded(State.EngineLoaded.EngineWithConfig(engine, config), _) =>
        State.EngineLoaded(State.EngineLoaded.EngineWithConfig(engine, config), scheduleIndexConfigChecking(noIndexStartingFailure))
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

  private [this] sealed trait State
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

  private val indexConfigCheckingSchedulerDelay = 5 second
  private val delayOfOldEngineShutdown = 10 seconds

  private val noIndexStartingFailure = Task.now(Left(StartingFailure("Cannot find index with ROR configuration")))

  sealed trait ForceReloadError
  object ForceReloadError {
    final case class CannotReload(startingFailure: StartingFailure) extends ForceReloadError
    case object ReloadingError extends ForceReloadError
    case object StoppedInstance extends ForceReloadError
    case object ConfigUpToDate extends ForceReloadError
  }
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

final class Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
  private [ror] def shutdown(): Unit = {
    httpClientsFactory.shutdown()
  }
}


