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
package tech.beshu.ror.settings.ror

import better.files.File
import io.circe.{Json, ParsingFailure}
import squants.information.Information
import tech.beshu.ror.settings.ror.RawRorSettingsYamlParser.ParsingRorSettingsError
import tech.beshu.ror.settings.ror.RawRorSettingsYamlParser.ParsingRorSettingsError.{InvalidContent, MoreThanOneRorSection, NoRorSection}
import tech.beshu.ror.utils.yaml.YamlParser

class RawRorSettingsYamlParser(maxSize: Information) {

  private val yamlParser: YamlParser = new YamlParser(Some(maxSize))

  def fromFile(file: File): Either[ParsingRorSettingsError, RawRorSettings] = {
    fromString(file.contentAsString)
  }

  def fromString(content: String): Either[ParsingRorSettingsError, RawRorSettings] = {
    handleParseResult(yamlParser.parse(content))
      .map(RawRorSettings(_, content))
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
}
object RawRorSettingsYamlParser {

  sealed trait ParsingRorSettingsError
  object ParsingRorSettingsError {
    case object NoRorSection extends ParsingRorSettingsError
    case object MoreThanOneRorSection extends ParsingRorSettingsError
    final case class InvalidContent(throwable: Throwable) extends ParsingRorSettingsError
  }
}