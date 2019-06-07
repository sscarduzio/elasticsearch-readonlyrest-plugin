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
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.Reason
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, CirceCoreFactory, CoreFactory}
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
import tech.beshu.ror.utils.TaskOps._
import tech.beshu.ror.utils.{EnvVarsProvider, JavaUuidProvider, OsEnvVarsProvider, UuidProvider}

import scala.concurrent.duration._
import scala.language.postfixOps

object Ror extends ReadonlyRest {

  val blockingScheduler: Scheduler= Scheduler.io("blocking-index-content-provider")

  override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  override protected implicit val clock: Clock = Clock.systemUTC()

  override protected val coreFactory: CoreFactory = {
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(envVarsProvider)
    new CirceCoreFactory
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
          case LoadEsConfigError.MalformedContent(file, ex) =>
            StartingFailure(s"Elasticsearch config file is malformed: [${file.pathAsString}]", Some(ex))
        })
    }
  }

  private def startRor(esConfig: EsConfig,
                       fileConfigLoader: FileConfigLoader,
                       indexConfigManager: IndexConfigManager,
                       auditSink: AuditSink) = {
    if (esConfig.forceLoadRorFromFile) {
      for {
        config <- EitherT(loadRorConfigFromFile(fileConfigLoader, auditSink))
        engine <- EitherT(loadRorCore(config, auditSink))
      } yield new RorInstance(engine, config, indexConfigManager, auditSink)
    } else {
      EitherT.pure[Task, StartingFailure](
        new RorInstance(
          indexConfigManager,
          auditSink,
          loadRorConfigFromFile(fileConfigLoader, auditSink)
        )
      )
    }
  }

  private def loadRorConfigFromFile(fileConfigLoader: FileConfigLoader, auditSink: AuditSink) = {
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
                                          auditSink: AuditSink,
                                          noIndexFallback: => Task[Either[StartingFailure, RawRorConfig]]) = {
    // todo: wait if cluster is ready?
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
      // todo: how to distinguish if core needs to be reloaded?
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
            // todo: move this log to the place where engine is reassigned
            logger.info("Readonly REST plugin core was loaded ...")
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

class RorInstance private (initialEngine: Option[(Engine, RawRorConfig)],
                           indexConfigManager: IndexConfigManager,
                           auditSink: AuditSink,
                           initialNoIndexFallback: Task[Either[StartingFailure, RawRorConfig]])
  extends Logging {

  def this(indexConfigManager: IndexConfigManager,
           auditSink: AuditSink,
           initialNoIndexFallback: Task[Either[StartingFailure, RawRorConfig]]) = {
    this(None, indexConfigManager, auditSink, initialNoIndexFallback)
  }

  def this(engine: Engine,
           config: RawRorConfig,
           indexConfigManager: IndexConfigManager,
           auditSink: AuditSink) = {
    this(Some((engine, config)), indexConfigManager, auditSink, noIndexStartingFailure)
  }

  private val instanceState: Atomic[State] =
    initialEngine match {
      case Some((engine, config)) => AtomicAny(State.EngineLoaded(engine, config, scheduleIndexConfigChecking(initialNoIndexFallback)))
      case None => AtomicAny(State.Initiated(scheduleIndexConfigChecking(initialNoIndexFallback)))
    }

  def engine: Option[Engine] = instanceState.get() match {
    case State.Initiated(_) => None
    case State.EngineLoaded(engine, _, _) => Some(engine)
    case State.Stopped => None
  }

  // todo: max one reload at time?
  def forceReloadFromIndex(): Task[Either[ForceReloadError, Unit]] = {
    val promise = CancelablePromise[Either[ForceReloadError, Unit]]()
    reloadEngine(noIndexStartingFailure)
        .runAsync {
          case Right(Right(state)) =>
            state match {
              case State.Initiated(_) =>
                logger.error(s"Unexpected state: Initialized")
                promise.success(Left(ForceReloadError.ReloadingError))
              case State.EngineLoaded(_, _, _) =>
                promise.success(Right(()))
              case State.Stopped =>
                promise.success(Left(ForceReloadError.StoppedInstance))
            }
          case Right(Left(startingFailure)) =>
            promise.success(Left(ForceReloadError.CannotReload(startingFailure)))
          case Left(ex) =>
            logger.error("Force reloading failed", ex)
            promise.success(Left(ForceReloadError.ReloadingError))
        }
    Task.fromCancelablePromise(promise)
  }

  // todo: use it
  def stop(): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        State.Stopped
      case State.EngineLoaded(engine, _, _) =>
        engine.shutdown()
        State.Stopped
      case State.Stopped =>
        State.Stopped
    }
  }

  private def scheduleIndexConfigChecking(noIndexFallback: Task[Either[StartingFailure, RawRorConfig]]): Cancelable = {
    // todo: check what's going on with doubled log
    scheduler.scheduleOnce(RorInstance.indexConfigCheckingSchedulerDelay) {
      reloadEngine(noIndexFallback)
        .runAsync {
          case Right(Right(_)) =>
          case Right(Left(startingFailure)) =>
            // todo: better log
            logger.warn(s"Checking index config failed: ${startingFailure.message}")
            scheduleNewConfigCheck()
          case Left(ex) =>
            // todo: better log
            if(logger.delegate.isDebugEnabled) logger.debug(s"Checking index config failed due to exception", ex)
            else logger.warn(s"Checking index config failed due to exception")
            scheduleNewConfigCheck()
        }
    }
  }

  private def reloadEngine(noIndexFallback: Task[Either[StartingFailure, RawRorConfig]]) = {
    (for {
      result <- EitherT(loadNewEngineFromIndex(noIndexFallback))
      (newEngine, newConfig) = result
      state <- EitherT.right[StartingFailure](applyNewEngine(newEngine, newConfig))
    } yield state).value
  }

  private def applyNewEngine(newEngine: Engine, newConfig: RawRorConfig) = {
    val promise = CancelablePromise[State]()
    instanceState.transform {
      case State.Initiated(cancelable) =>
        cancelable.cancel()
        // todo: log (new engine loaded)
        val newState = State.EngineLoaded(newEngine, newConfig, scheduleIndexConfigChecking(noIndexStartingFailure))
        promise.success(newState)
        newState
      case State.EngineLoaded(oldEngine, _, _) =>
        scheduleDelayedShutdown(oldEngine)
        // todo: log (new engine loaded)
        val newState = State.EngineLoaded(newEngine, newConfig, scheduleIndexConfigChecking(noIndexStartingFailure))
        promise.success(newState)
        newState
      case State.Stopped =>
        newEngine.shutdown()
        val newState = State.Stopped
        promise.success(newState)
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
    Ror
      .loadRorConfigFromIndex(indexConfigManager, auditSink, noIndexFallback)
      .flatMap {
        case Right(config) =>
          Ror
            .loadRorCore(config, auditSink)
            .map(_.map((_, config)))
        case Left(failure) =>
          Task.now(Left(failure))
      }
  }

  private def scheduleNewConfigCheck(): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        State.Initiated(scheduleIndexConfigChecking(noIndexStartingFailure))
      case State.EngineLoaded(engine, config, _) =>
        State.EngineLoaded(engine, config, scheduleIndexConfigChecking(noIndexStartingFailure))
      case State.Stopped =>
        State.Stopped
    }
  }

  private [this] sealed trait State
  private object State {
    sealed case class Initiated(scheduledInitLoadingJob: Cancelable) extends State
    sealed case class EngineLoaded(engine: Engine, currentConfig: RawRorConfig, scheduledInitLoadingJob: Cancelable) extends State
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
  }
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

final class Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
  private [ror] def shutdown(): Unit = {
    httpClientsFactory.shutdown()
  }
}


