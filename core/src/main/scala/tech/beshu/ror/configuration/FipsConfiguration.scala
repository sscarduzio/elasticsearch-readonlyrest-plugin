package tech.beshu.ror.configuration

import better.files.File
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.configuration.FipsConfiguration.FipsMode
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.providers.OsEnvVarsProvider

import java.nio.file.Path

final case class FipsConfiguration(fipsMode: FipsMode)

object FipsConfiguration {

  def load(esConfigFolderPath: Path): Task[Either[MalformedSettings, FipsConfiguration]] = {
    val rorConfig = FileConfigLoader.create(esConfigFolderPath).rawConfigFile
    load(rorConfig)
  }

  def load(config: File): Task[Either[MalformedSettings, FipsConfiguration]] = Task {
    new EsConfigFileLoader[FipsConfiguration]().loadConfigFromFile(config, "ROR FIPS Configuration")
  }

  private implicit val envVarsProvider: OsEnvVarsProvider.type = OsEnvVarsProvider

  private implicit val fipsModeDecoder: Decoder[FipsMode] = {
    Decoder.decodeString.emap {
      case "NON_FIPS" => Right(FipsMode.NonFips)
      case "SSL_ONLY" => Right(FipsMode.SslOnly)
      case _ => Left("Invalid configuration option for FIPS MODE")
    }
  }

  private implicit val fipsConfigurationDecoder: Decoder[FipsConfiguration] = {
    Decoder.instance { c =>
      c.downField("readonlyrest")
        .getOrElse[FipsMode]("fips_mode")(FipsMode.NonFips)
        .map(FipsConfiguration(_))
    }
  }

  sealed trait FipsMode
  object FipsMode {
    case object NonFips extends FipsMode
    case object SslOnly extends FipsMode
  }
}
