package tech.beshu.ror.configuration

import better.files.File
import io.circe.Decoder
import tech.beshu.ror.utils.yaml

trait LoadFromEsConfig {

  protected def loadConfigFromFile[CONFIG: Decoder](file: File,
                                                    configName: String): Either[MalformedSettings, CONFIG] = {
    file.fileReader { reader =>
      yaml
        .parser
        .parse(reader)
        .left.map(e => MalformedSettings(s"Cannot parse file ${file.pathAsString} content. Cause: ${e.message}"))
        .right
        .flatMap { json =>
          implicitly[Decoder[CONFIG]]
            .decodeJson(json)
            .left.map(e => MalformedSettings(s"Invalid $configName configuration"))
        }
    }
  }
}

final case class MalformedSettings(message: String)
