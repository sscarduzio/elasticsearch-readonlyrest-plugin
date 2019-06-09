package tech.beshu.ror.configuration

import java.nio.file.Path

import better.files.File
import cats.data.EitherT
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.{FileNotFound, MalformedContent}
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings
import tech.beshu.ror.utils.JsonFile

final case class EsConfig(rorEsLevelSettings: RorEsLevelSettings, ssl: RorSsl)

object EsConfig {

  def from(esConfigFolderPath: Path): Task[Either[LoadEsConfigError, EsConfig]] = {
    import decoders._
    val configFile = File(s"${esConfigFolderPath.toAbsolutePath}/elasticsearch.yml")
    (for {
      _ <- EitherT.fromEither[Task](Either.cond(configFile.exists, (), FileNotFound(configFile)))
      rorEsLevelSettings <- EitherT.fromEither[Task](new JsonFile(configFile).parse[RorEsLevelSettings].left.map(MalformedContent(configFile, _)))
      ssl <- EitherT.right[LoadEsConfigError](RorSsl.load(esConfigFolderPath))
    } yield EsConfig(rorEsLevelSettings, ssl)).value
  }

  final case class RorEsLevelSettings(forceLoadRorFromFile: Boolean)

  sealed trait LoadEsConfigError
  object LoadEsConfigError {
    final case class FileNotFound(file: File) extends LoadEsConfigError
    final case class MalformedContent(file: File, message: String) extends LoadEsConfigError
  }

  private object decoders {
    implicit val rorEsLevelSettingsDecoder: Decoder[RorEsLevelSettings] = Decoder.instance { c =>
      for {
        forceLoadFromFile <- c.downField("readonlyrest").downField("force_load_from_file").as[Option[Boolean]]
      } yield RorEsLevelSettings(
        forceLoadFromFile.getOrElse(false)
      )
    }
  }

}
