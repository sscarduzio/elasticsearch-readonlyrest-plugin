package tech.beshu.ror

import java.nio.file.Path

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.acl.factory.AsyncHttpClientsFactory
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.{EsConfig, FileConfigLoader}
import tech.beshu.ror.es.{AuditSink, IndexContentProvider}
import tech.beshu.ror.utils.OsEnvVarsProvider

object RorEngine {

  private val envVarsProvider = OsEnvVarsProvider

  def start(esConfigPath: Path,
            auditSink: AuditSink,
            indexContentProvider: IndexContentProvider): Task[Either[StartingFailure, Unit]] = {
    (for {
      fileConfigLoader <- createFileConfigLoader(esConfigPath)
      esConfig <- loadEsConfig(esConfigPath)
      //_ = if (esConfig.forceLoadRorFromFile) fileConfigLoader.load()
    } yield ()).value
  }

  private def createFileConfigLoader(esConfigPath: Path) = {
    EitherT.pure[Task, StartingFailure](new FileConfigLoader(esConfigPath, envVarsProvider))
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
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

final class Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
  def shutdown(): Unit = {
    httpClientsFactory.shutdown()
  }
}


