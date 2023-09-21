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
package tech.beshu.ror.es.dlsfls

import tech.beshu.ror.es.dlsfls.JsonPolicyBasedFilterer.JSON
import tech.beshu.ror.fls.FieldsPolicy
import ujson._

// todo: move to core
class JsonPolicyBasedFilterer(policy: FieldsPolicy) {

  def filteredJson(json: JSON): JSON = {
    buildFilteredJson(json, collectedField = "")
  }

  private def buildFilteredJson(json: JSON, collectedField: String): JSON = {
    json match {
      case Obj(map) => Obj.from {
          map
            .flatMap { case (fieldName, fieldValue) =>
              val newlyCollectedField = currentField(collectedField, fieldName)
              if (policy.canKeep(newlyCollectedField)) {
                Some(fieldName -> buildFilteredJson(fieldValue, newlyCollectedField))
              } else {
                None
              }
            }
        }
      case Arr(values) =>
        values.map { value =>
          // todo: None? looks like a problem
          buildFilteredJson(value, collectedField)
        }
      case str@Str(_) => str
      case num@Num(_) => num
      case bool@Bool(_) => bool
      case Null => Null
    }
  }

  private def currentField(collectedField: String, currentFieldPart: String) = {
    if(collectedField.isEmpty) currentFieldPart else s"$collectedField.$currentFieldPart"
  }

}
object JsonPolicyBasedFilterer {
  type JSON = ujson.Value
}
