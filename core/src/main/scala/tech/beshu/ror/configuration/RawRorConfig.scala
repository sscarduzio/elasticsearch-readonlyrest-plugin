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

import better.files.File
import cats.effect.Resource
import cats.{Eq, Show}
import io.circe.{Json, ParsingFailure}
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.configuration.RawRorConfig.ParsingRorConfigError.{InvalidContent, MoreThanOneRorSection, NoRorSection}
import tech.beshu.ror.implicits.*

import java.io.StringReader

final case class RawRorConfig(configJson: Json, raw: String)

object RawRorConfig {

  def fromFile(file: File)
              (implicit systemContext: SystemContext): Task[Either[ParsingRorConfigError, RawRorConfig]] = {
    fromString(file.contentAsString)
  }

  def fromString(content: String)
                (implicit systemContext: SystemContext): Task[Either[ParsingRorConfigError, RawRorConfig]] = {
    val contentResource = Resource.make(Task(new StringReader(content))) { reader => Task(reader.close()) }
    contentResource.use { reader => Task {
      handleParseResult(systemContext.yamlParser.parse(reader))
        .map(RawRorConfig(_, content))
    }}
  }

  private def handleParseResult(result: Either[ParsingFailure, Json]) = {
    result
      .left.map(InvalidContent.apply)
      .flatMap { json => validateRorJson(json) }
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
      case NoRorSection => "Cannot find any 'readonlyrest' section in settings"
      case MoreThanOneRorSection => "Only one 'readonlyrest' section is required"
      case InvalidContent(ex) => s"Settings content is malformed. Details: ${ex.getMessage.show}"
    }
  }

  implicit val eq: Eq[RawRorConfig] = Eq.fromUniversalEquals
}
