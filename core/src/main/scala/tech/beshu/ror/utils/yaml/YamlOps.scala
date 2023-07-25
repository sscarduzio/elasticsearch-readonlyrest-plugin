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

import io.circe.{Json, JsonObject}
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

  def jsonWithOneLinerKeysToRegularJson(json: Json): Json = {
    json.mapObject { obj =>
      val list = obj.toMap.toList
        .map { case (key, value) =>
          key.split("\\.").toList match {
            case Nil =>
              (key, value)
            case fst :: rest =>
              (fst, rest.reverse.foldLeft(value) { case (acc, elem) => Json.obj(elem -> acc) })
          }
        }
      list
        .map(tuple => JsonObject(tuple))
        .foldLeft(JsonObject.empty)(_.deepMerge(_))
    }
  }
}
