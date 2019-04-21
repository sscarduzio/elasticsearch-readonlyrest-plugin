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
package tech.beshu.ror.acl.utils

import io.circe.Json
import tech.beshu.ror.com.fasterxml.jackson.databind.ObjectMapper
import tech.beshu.ror.com.fasterxml.jackson.dataformat.yaml.YAMLMapper

object YamlOps {

  private val objectMapper = new ObjectMapper()
  private val yamlMapper = new YAMLMapper()

  def jsonToYamlString(json: Json): String = {
    yamlMapper
      .writeValueAsString(objectMapper.readTree(json.noSpaces))
      .replace("---\n", "")
  }
}
