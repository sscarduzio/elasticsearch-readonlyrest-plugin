package tech.beshu.ror

import java.nio.file.Path
import java.time.Clock

import cats.data.EitherT
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler.Implicits.global
import monix.execution.Scheduler.{global => scheduler}
import monix.execution.atomic.Atomic
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, CoreFactory}
import tech.beshu.ror.acl.logging.{AclLoggingDecorator, AuditingTool}
import tech.beshu.ror.acl.utils.StaticVariablesResolver
import tech.beshu.ror.acl.{Acl, AclStaticContext}
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
import scala.util.{Failure, Success, Try}

object Ror extends Ror {

  override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  override protected implicit val clock: Clock = Clock.systemUTC()
  override protected implicit val uuidProvider: UuidProvider = JavaUuidProvider
  override protected implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(envVarsProvider)

}

trait Ror {

  protected def envVarsProvider: EnvVarsProvider
  protected implicit def clock: Clock
  protected implicit def uuidProvider: UuidProvider
  protected implicit def resolver: StaticVariablesResolver

  private val aclFactory = new CoreFactory

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
                       auditSink: AuditSink) = EitherT {
    val startEngineTask = if (esConfig.forceLoadRorFromFile) {
      loadRorConfigFromFile(fileConfigLoader, auditSink)
    } else {
      loadRorConfigFromIndex(
        indexConfigLoader,
        auditSink,
        loadRorConfigFromFile(fileConfigLoader, auditSink)
      )
    }
    startEngineTask.map(_.map(engine => new RorInstance(engine, indexConfigLoader, auditSink)))
  }

  private def loadRorConfigFromFile(fileConfigLoader: FileConfigLoader, auditSink: AuditSink) = {
    fileConfigLoader
      .load()
      .flatMap {
        case Right(config) =>
          loadRorCore(config, auditSink)
        case Left(NoRorSection) =>
          lift(StartingFailure("Cannot find any 'readonlyrest' section in config"))
        case Left(MoreThanOneRorSection) =>
          lift(StartingFailure("Only one 'readonlyrest' section is required"))
        case Left(InvalidContent(ex)) =>
          lift(StartingFailure("Config file content is malformed", Some(ex)))
        case Left(SpecializedError(FileNotExist(file))) =>
          lift(StartingFailure(s"Cannot find config file: ${file.pathAsString}"))
      }
  }

  private[ror] def loadRorConfigFromIndex(indexConfigLoader: IndexConfigLoader,
                                          auditSink: AuditSink,
                                          noIndexFallback: Task[Either[StartingFailure, Engine]]) = {
    indexConfigLoader
      .load()
      .flatMap {
        case Right(config) =>
          loadRorCore(config, auditSink)
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

  private def loadRorCore(config: RawRorConfig, auditSink: AuditSink) = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    aclFactory
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

class RorInstance(initialEngine: Engine,
                  indexConfigLoader: IndexConfigLoader,
                  auditSink: AuditSink) {

  private val workingEngine = Atomic(Option(initialEngine, scheduleIndexConfigChecking()))

  def stop(): Unit = {
    workingEngine.transform {
      case Some((engine, cancelable)) =>
        cancelable.cancel()
        engine.shutdown()
        None
      case None =>
        None
    }
  }

  private def scheduleIndexConfigChecking(): Cancelable = {
    scheduler.scheduleOnce(RorInstance.indexConfigCheckingSchedulerDelay) {
      val loadEngineAction = Ror.loadRorConfigFromIndex(indexConfigLoader, auditSink, noIndexStartingFailure)
      loadEngineAction
        .andThen {
          handleLoadEngineResultAndRescheduleChecking(loadEngineAction, auditSink)
        }
        .runAsyncAndForget
    }
  }

  private def scheduleDelayedShutdown(engine: Engine) = {
    scheduler.scheduleOnce(RorInstance.delayOfOldEngineShutdown) {
      engine.shutdown()
    }
  }

  private def handleLoadEngineResultAndRescheduleChecking(reloadEngineJob: Task[Either[StartingFailure, Engine]],
                                                          auditSink: AuditSink): PartialFunction[Try[Either[StartingFailure, Engine]], Unit] = {
    case Success(Right(newEngine)) =>
      workingEngine.transform {
        case Some((oldEngine, _)) =>
          scheduleDelayedShutdown(oldEngine)
          Some(newEngine, scheduleIndexConfigChecking())
        case None =>
          newEngine.shutdown()
          None
      }
    case Success(Left(failure)) =>
      // todo: log
      workingEngine.transform {
        case Some((oldEngine, _)) =>
          Some(oldEngine, scheduleIndexConfigChecking())
        case None =>
          None
      }
    case Failure(exception) =>
      // todo: log
      workingEngine.transform {
        case Some((oldEngine, _)) =>
          Some(oldEngine, scheduleIndexConfigChecking())
        case None =>
          None
      }
  }

  private lazy val noIndexStartingFailure = Task.now(Left(StartingFailure("Cannot find index with ROR configuration")))
}

object RorInstance {

  private val indexConfigCheckingSchedulerDelay = 1 second
  private val delayOfOldEngineShutdown = 10 seconds
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

private final class Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
  def shutdown(): Unit = {
    httpClientsFactory.shutdown()
  }
}


