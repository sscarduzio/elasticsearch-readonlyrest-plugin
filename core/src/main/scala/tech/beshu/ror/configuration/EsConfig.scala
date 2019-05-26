package tech.beshu.ror.configuration

import java.nio.file.Path

import better.files.File
import monix.eval.Task
import org.yaml.snakeyaml.Yaml
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.{FileNotFound, MalformedContent}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.Try

final case class EsConfig(forceLoadRorFromFile: Boolean)

object EsConfig {

  def from(path: Path): Task[Either[LoadEsConfigError, EsConfig]] = Task {
    val config = File(s"${path.toAbsolutePath}/elasticsearch.yml")
    for {
      _ <- Either.cond(config.exists, (), FileNotFound(config))
      content <- parseFileContent(config)
    } yield EsConfig(
      get(content, "readonlyrest.force_load_from_file", false)
    )
  }

  private def parseFileContent(file: File) = {
    val yaml = new Yaml()
    file.inputStream.apply { is =>
      Try(yaml.load[java.util.Map[String, Object]](is))
        .toEither
        .left.map(MalformedContent(file, _))
        .map(_.asScala.toMap[String, Any])
    }
  }

  private def get[T : ClassTag](config: Map[String, Any], key: String, default: T) = {
    config
      .get(key)
      .collect { case value: T => value }
      .getOrElse(default)
  }

  sealed trait LoadEsConfigError
  object LoadEsConfigError {
    final case class FileNotFound(file: File) extends LoadEsConfigError
    final case class MalformedContent(file: File, throwable: Throwable) extends LoadEsConfigError
  }
}
