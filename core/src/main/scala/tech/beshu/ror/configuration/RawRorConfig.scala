/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.configuration

import java.util.regex.Pattern

import better.files.File
import cats.{Eq, Show}
import io.circe.yaml.parser
import io.circe.{Json, ParsingFailure}
import tech.beshu.ror.configuration.RawRorConfig.ParsingRorConfigError.{InvalidContent, MoreThanOneRorSection, NoRorSection}
import scala.collection.JavaConverters._

final case class RawRorConfig(rawConfig: Json)

object RawRorConfig {

  // todo: this is better implementation. Replace with it when RORDEV-6 will be finished
  //  def fromFile(file: File): Either[ParsingRorConfigError, RawRorConfig] = {
  //    file.inputStream.apply { is =>
  //      handleParseResult(parser.parse(new InputStreamReader(is)))
  //    }
  //  }

  def fromFile(file: File): Either[ParsingRorConfigError, RawRorConfig] = {
    handleParseResult(parser.parse(replaceEnvVars(file.contentAsString)))
  }

  def fromString(content: String): Either[ParsingRorConfigError, RawRorConfig] = {
    handleParseResult(parser.parse(replaceEnvVars(content)))
  }

  private def handleParseResult(result: Either[ParsingFailure, Json]) = {
    result
      .left.map(InvalidContent.apply)
      .flatMap { json => validateRorJson(json) }
      .map(RawRorConfig.apply)
  }

  // fixme: RORDEV-6 will do it better
  private def replaceEnvVars(rawYaml: String) = {
    System.getenv.keySet.asScala.fold(rawYaml) {
      case (yaml, envKey) =>
        yaml.replaceAll(Pattern.quote("${" + envKey + "}"), System.getenv(envKey))
    }
  }

  private def validateRorJson(json: Json) = {
    json \\ "readonlyrest" match {
      case Nil => Left(NoRorSection)
      case _ :: Nil => Right(json)
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

  implicit val eq: Eq[RawRorConfig] = Eq.fromUniversalEquals
}
