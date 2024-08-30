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

import tech.beshu.ror.configuration.RorProperties
import tech.beshu.ror.org.yaml.snakeyaml.LoaderOptions
import tech.beshu.ror.providers.PropertiesProvider
import java.io.Reader
import io.circe._

object RorYamlParser {

  def parse(yaml: Reader)(implicit propertiesProvider: PropertiesProvider): Either[ParsingFailure, Json] = {
    tech.beshu.ror.utils.yaml.parser.parse(yaml, createLoaderOptions())
  }

  private def createLoaderOptions()(implicit propertiesProvider: PropertiesProvider): LoaderOptions = {
    val options = new LoaderOptions
    options.setCodePointLimit(RorProperties.rorSettingsMaxSize.toBytes.toInt)
    options
  }
}
