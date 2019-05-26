package tech.beshu.ror.boot

import java.nio.file.Path
import java.time.Clock

import cats.data.EitherT
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.Scheduler.{global => scheduler}
import monix.execution.atomic.{Atomic, AtomicAny}
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.Reason
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, CirceCoreFactory, CoreFactory}
import tech.beshu.ror.acl.logging.{AclLoggingDecorator, AuditingTool}
import tech.beshu.ror.acl.utils.StaticVariablesResolver
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.boot.RorInstance.noIndexStartingFailure
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError._
import tech.beshu.ror.configuration.ConfigLoader.RawRorConfig
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError.FileNotExist
import tech.beshu.ror.configuration.IndexConfigLoader.IndexConfigError.IndexConfigNotExist
import tech.beshu.ror.configuration.{EsConfig, FileConfigLoader, IndexConfigLoader}
import tech.beshu.ror.es.{AuditSink, IndexContentProvider}
import tech.beshu.ror.utils.TaskOps._
import tech.beshu.ror.utils.{EnvVarsProvider, JavaUuidProvider, OsEnvVarsProvider, UuidProvider}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

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

trait ReadonlyRest {

  protected def envVarsProvider: EnvVarsProvider
  protected implicit def clock: Clock
  protected def coreFactory: CoreFactory

  def start(esConfigPath: Path,
            auditSink: AuditSink,
            indexContentProvider: IndexContentProvider): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      fileConfigLoader <- createFileConfigLoader(esConfigPath)
      indexConfigLoader <- createIndexConfigLoader(indexContentProvider)
      esConfig <- loadEsConfig(esConfigPath)
      instance <- startRor(esConfig, fileConfigLoader, indexConfigLoader, auditSink)
    } yield instance).value
  }

  private def createFileConfigLoader(esConfigPath: Path) = {
    EitherT.pure[Task, StartingFailure](new FileConfigLoader(esConfigPath, envVarsProvider))
  }

  private def createIndexConfigLoader(indexContentProvider: IndexContentProvider) = {
    EitherT.pure[Task, StartingFailure](new IndexConfigLoader(indexContentProvider))
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
                       indexConfigLoader: IndexConfigLoader,
                       auditSink: AuditSink) = {
    if (esConfig.forceLoadRorFromFile) {
      for {
        config <- EitherT(loadRorConfigFromFile(fileConfigLoader, auditSink))
        engine <- EitherT(loadRorCore(config, auditSink))
      } yield new RorInstance(engine, config, indexConfigLoader, auditSink)
    } else {
      EitherT.pure[Task, StartingFailure](
        new RorInstance(
          indexConfigLoader,
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
        case Left(NoRorSection) =>
          Left(StartingFailure("Cannot find any 'readonlyrest' section in config"))
        case Left(MoreThanOneRorSection) =>
          Left(StartingFailure("Only one 'readonlyrest' section is required"))
        case Left(InvalidContent(ex)) =>
          Left(StartingFailure("Config file content is malformed", Some(ex)))
        case Left(SpecializedError(FileNotExist(file))) =>
          Left(StartingFailure(s"Cannot find config file: ${file.pathAsString}"))
      }
  }

  private[ror] def loadRorConfigFromIndex(indexConfigLoader: IndexConfigLoader,
                                          auditSink: AuditSink,
                                          noIndexFallback: => Task[Either[StartingFailure, RawRorConfig]]) = {
    // todo: wait if cluster is ready?
    indexConfigLoader
      .load()
      .flatMap {
        case Right(config) =>
          Task.now(Right(config))
        case Left(NoRorSection) =>
          lift(StartingFailure("Cannot find any 'readonlyrest' section in config"))
        case Left(MoreThanOneRorSection) =>
          lift(StartingFailure("Only one 'readonlyrest' section is required"))
        case Left(InvalidContent(ex)) =>
          lift(StartingFailure("Config file content is malformed", Some(ex)))
        case Left(SpecializedError(IndexConfigNotExist)) =>
          noIndexFallback
      }
  }

  private[ror] def loadRorCore(config: RawRorConfig, auditSink: AuditSink) = {
      // todo: how to distinguish if core needs to be reloaded?
    val httpClientsFactory = new AsyncHttpClientsFactory
    coreFactory
      .createCoreFrom(config, httpClientsFactory)
      .map { result =>
        result
          .right
          .map { coreSettings =>
            new Engine(
              new AclLoggingDecorator(
                coreSettings.aclEngine,
                coreSettings.auditingSettings.map(new AuditingTool(_, auditSink))
              ),
              coreSettings.aclStaticContext,
              httpClientsFactory
            )
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
                           indexConfigLoader: IndexConfigLoader,
                           auditSink: AuditSink,
                           initialNoIndexFallback: Task[Either[StartingFailure, RawRorConfig]])
  extends Logging {

  def this(indexConfigLoader: IndexConfigLoader,
           auditSink: AuditSink,
           initialNoIndexFallback: Task[Either[StartingFailure, RawRorConfig]]) = {
    this(None, indexConfigLoader, auditSink, initialNoIndexFallback)
  }

  def this(engine: Engine,
           config: RawRorConfig,
           indexConfigLoader: IndexConfigLoader,
           auditSink: AuditSink) = {
    this(Some((engine, config)), indexConfigLoader, auditSink, noIndexStartingFailure)
  }

  private val instanceState: Atomic[State] =
    initialEngine match {
      case Some((engine, config)) => AtomicAny(State.EngineLoaded(engine, config, scheduleIndexConfigChecking(initialNoIndexFallback)))
      case None => AtomicAny(State.Initiated(scheduleIndexConfigChecking(initialNoIndexFallback)))
    }

  def engine: Option[Engine] = instanceState.get() match {
    case State.Initiated(_) => None
    case State.LoadingEngine(engineConfigOpt) => engineConfigOpt.map(_._1)
    case State.EngineLoaded(engine, _, _) => Some(engine)
    case State.Stopped => None
  }

  // todo: use it
  def stop(): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        State.Stopped
      case State.LoadingEngine(engineConfigOpt) =>
        engineConfigOpt.foreach(_._1.shutdown())
        State.Stopped
      case State.EngineLoaded(engine, _, _) =>
        engine.shutdown()
        State.Stopped
      case State.Stopped =>
        State.Stopped
    }
  }

  private def scheduleIndexConfigChecking(noIndexFallback: Task[Either[StartingFailure, RawRorConfig]]): Cancelable = {
    scheduler.scheduleOnce(RorInstance.indexConfigCheckingSchedulerDelay) {
      val loadEngineAction = Ror.loadRorConfigFromIndex(indexConfigLoader, auditSink, noIndexFallback)
      loadEngineAction
        .andThen {
          case Success(Right(newConfig)) =>
            newConfigFound(newConfig, auditSink)
          case Success(Left(failure)) =>
            // todo: better log
            logger.warn(s"Checking index config failed: ${failure.message}")
            scheduleNewConfigCheck()
          case Failure(exception) =>
            // todo: better log
            if(logger.delegate.isDebugEnabled) logger.debug(s"Checking index config failed due to exception", exception)
            else logger.warn(s"Checking index config failed due to exception")
            scheduleNewConfigCheck()
        }
        .runAsyncAndForget
    }
  }

  private def scheduleDelayedShutdown(engine: Engine) = {
    scheduler.scheduleOnce(RorInstance.delayOfOldEngineShutdown) {
      engine.shutdown()
    }
  }

  private def newConfigFound(newConfig: RawRorConfig, auditSink: AuditSink): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        loadNewEngineUsingConfig(newConfig)
        State.LoadingEngine(None)
      case state:State.LoadingEngine =>
        logger.error("Unexpected state: Loading Engine")
        state
      case state@State.EngineLoaded(engine, currentConfig, _) =>
        if(currentConfig == newConfig) state
        else {
          loadNewEngineUsingConfig(newConfig)
          State.LoadingEngine(Some(engine, currentConfig))
        }
      case State.Stopped =>
        State.Stopped
    }
  }

  private def loadNewEngineUsingConfig(config: RawRorConfig): Unit = {
    Ror
      .loadRorCore(config, auditSink)
      .onErrorHandle(ex => Left(StartingFailure("Loading new core failed", Some(ex))))
      .foreach {
        case Right(newEngine) =>
          instanceState.transform {
            case State.Initiated(cancelable) =>
              cancelable.cancel()
              State.EngineLoaded(newEngine, config, scheduleIndexConfigChecking(noIndexStartingFailure))
            case State.LoadingEngine(oldEngine) =>
              oldEngine.foreach { case (engine, _) => scheduleDelayedShutdown(engine) }
              State.EngineLoaded(newEngine, config, scheduleIndexConfigChecking(noIndexStartingFailure))
            case state: State.EngineLoaded =>
              logger.error("Unexpected state: Engine Loaded")
              newEngine.shutdown()
              state
            case State.Stopped =>
              newEngine.shutdown()
              State.Stopped
          }
        case Left(failure) =>
          // todo: better log
          logger.warn(failure.message)
          scheduleNewConfigCheck()
      }
  }

  private def scheduleNewConfigCheck(): Unit = {
    instanceState.transform {
      case State.Initiated(_) =>
        State.Initiated(scheduleIndexConfigChecking(noIndexStartingFailure))
      case State.LoadingEngine(Some((oldEngine, oldConfig))) =>
        State.EngineLoaded(oldEngine, oldConfig, scheduleIndexConfigChecking(noIndexStartingFailure))
      case State.LoadingEngine(None) =>
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
    sealed case class LoadingEngine(oldEngine: Option[(Engine, RawRorConfig)]) extends State
    sealed case class EngineLoaded(engine: Engine, currentConfig: RawRorConfig, scheduledInitLoadingJob: Cancelable) extends State
    case object Stopped extends State
  }
}

object RorInstance {

  private val indexConfigCheckingSchedulerDelay = 5 second
  private val delayOfOldEngineShutdown = 10 seconds

  private val noIndexStartingFailure = Task.now(Left(StartingFailure("Cannot find index with ROR configuration")))
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

final class Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
  private [ror] def shutdown(): Unit = {
    httpClientsFactory.shutdown()
  }
}


