package tech.beshu.ror.configuration.loader.external

import cats.Show
import io.circe.generic.extras.Configuration
import tech.beshu.ror.configuration.loader.LoadedRorConfig

package object dto {
  implicit val configuration: Configuration = Configuration.default.withDiscriminator("type")
  implicit val showLoadedConfigError: Show[LoadedRorConfig.Error] = Show.show {
    {
      case LoadedRorConfig.FileNotExist(path) => s"""file not exist: ${path.value}"""
      case LoadedRorConfig.FileParsingError(message) => s"""file parsing error: ${message}"""
      case LoadedRorConfig.EsFileNotExist(path) => s"""es file not exist: ${path.value}"""
      case LoadedRorConfig.EsFileMalformed(path, message) => s"""es file malformed: ${path} ${message}"""
      case LoadedRorConfig.IndexParsingError(message) => s"""index parsing error: ${message}"""
      case LoadedRorConfig.IndexUnknownStructure => "index unknown structure"
    }
  }

}
