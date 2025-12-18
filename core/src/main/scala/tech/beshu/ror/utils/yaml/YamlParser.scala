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
package tech.beshu.ror.utils.yaml

import better.files.*
import io.circe.*
import squants.information.Information
import tech.beshu.ror.org.yaml.snakeyaml.LoaderOptions

import java.io.{Reader, StringReader}

class YamlParser(maxSize: Option[Information] = None) {

  def parse(yaml: Reader): Either[ParsingFailure, Json] = {
    tech.beshu.ror.utils.yaml.parser.parse(yaml, loaderOptions)
  }
  
  def parse(file: File): Either[ParsingFailure, Json] = {
    file.fileReader { reader => parse(reader) }
  }
  
  def parse(yamlContent: String): Either[ParsingFailure, Json] = {
    parse(new StringReader(yamlContent))
  }

  private lazy val loaderOptions: LoaderOptions = {
    val options = new LoaderOptions
    maxSize.foreach { m => options.setCodePointLimit(m.toBytes.toInt) }
    options
  }
}
