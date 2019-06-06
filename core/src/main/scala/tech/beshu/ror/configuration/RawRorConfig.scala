package tech.beshu.ror.configuration

import java.io.InputStreamReader

import better.files.File
import cats.Show
import io.circe.yaml.parser
import io.circe.{Json, ParsingFailure}
import tech.beshu.ror.configuration.RawRorConfig.ParsingRorConfigError.{InvalidContent, MoreThanOneRorSection, NoRorSection}


final case class RawRorConfig(rawConfig: Json)

object RawRorConfig {

  def fromFile(file: File): Either[ParsingRorConfigError, RawRorConfig] = {
    file.inputStream.apply { is =>
      handleParseResult(parser.parse(new InputStreamReader(is)))
    }
  }

  def fromString(content: String): Either[ParsingRorConfigError, RawRorConfig] = {
    handleParseResult(parser.parse(content))
  }

  private def handleParseResult(result: Either[ParsingFailure, Json]) = {
    result
      .left.map(InvalidContent.apply)
      .flatMap { json => validateRorJson(json) }
      .map(RawRorConfig.apply)
  }

  private def validateRorJson(json: Json): Either[ParsingRorConfigError, Json] = {
    json \\ "readonlyrest" match {
      case Nil => Left(NoRorSection)
      case one :: Nil => Right(one)
      case _ => Left(MoreThanOneRorSection)
    }
  }

  sealed trait ParsingRorConfigError
  object ParsingRorConfigError {
    case object NoRorSection extends ParsingRorConfigError
    case object MoreThanOneRorSection extends ParsingRorConfigError
    final case class InvalidContent(throwable: Throwable) extends ParsingRorConfigError

    implicit val show: Show[ParsingRorConfigError] = Show.show {
      case NoRorSection => "Cannot find any 'readonlyrest' section in config"
      case MoreThanOneRorSection => "Only one 'readonlyrest' section is required"
      case InvalidContent(_) => "Config file content is malformed"
    }
  }
}
