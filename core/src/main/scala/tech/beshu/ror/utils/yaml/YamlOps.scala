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

import java.io.{Reader, StringReader}

import io.circe.{Json, JsonNumber, JsonObject, ParsingFailure}
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes._
import tech.beshu.ror.com.fasterxml.jackson.databind.ObjectMapper
import tech.beshu.ror.com.fasterxml.jackson.dataformat.yaml.YAMLMapper

object YamlOps {

  def jsonToYamlString(json: Json): String = {
    val objectMapper = new ObjectMapper()
    val yamlMapper = new YAMLMapper()
    yamlMapper
      .writeValueAsString(objectMapper.readTree(json.noSpaces))
      .replace("---\n", "")
  }

}
