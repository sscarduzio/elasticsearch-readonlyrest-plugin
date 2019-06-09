package tech.beshu.ror.utils

import better.files.File
import io.circe.Decoder
import io.circe.yaml.parser

class JsonFile(file: File) {

  def parse[T](implicit decoder: Decoder[T]): Either[String, T] = {
    file.fileReader { reader =>
      parser
        .parse(reader)
        .left.map(_.message)
        .right
        .flatMap { json =>
          decoder
            .decodeJson(json)
            .left.map(_.message)
        }
    }
  }
}


