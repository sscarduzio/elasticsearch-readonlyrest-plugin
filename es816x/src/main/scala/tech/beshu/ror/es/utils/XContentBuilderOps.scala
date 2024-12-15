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
package tech.beshu.ror.es.utils

import org.elasticsearch.xcontent.XContentBuilder
import ujson.*

import scala.language.implicitConversions

class XContentBuilderOps(val builder: XContentBuilder) extends AnyVal {

  def json(json: Value): XContentBuilder = {
    applyJsonToBuilder(builder, None, json)
  }

  private def applyJsonToBuilder(builder: XContentBuilder,
                                 fieldName: Option[String],
                                 json: Value): XContentBuilder = {
    def startObject(currentBuilder: XContentBuilder) = fieldName match {
      case Some(name) => currentBuilder.startObject(name)
      case None => currentBuilder.startObject()
    }

    def startArray(currentBuilder: XContentBuilder) = fieldName match {
      case Some(name) => currentBuilder.startArray(name)
      case None => currentBuilder.startArray()
    }

    json match {
      case Obj(map) =>
        map
          .foldLeft(startObject(builder)) {
            case (currentBuilder, (fieldName, fieldValue)) =>
              applyJsonToBuilder(currentBuilder, Some(fieldName), fieldValue)
          }
          .endObject()
      case Arr(values) =>
        values
          .foldLeft(startArray(builder)) {
            case (currentBuilder, arrayObject) =>
              applyJsonToBuilder(currentBuilder, None, arrayObject)
          }
          .endArray()
      case Str(value) =>
        fieldName match {
          case Some(aKey) => builder.field(aKey, value)
          case None => builder.value(value)
        }
      case Num(value) =>
        fieldName match {
          case Some(aKey) => builder.field(aKey, value)
          case None => builder.value(value)
        }
      case Bool(value) =>
        fieldName match {
          case Some(aKey) => builder.field(aKey, value)
          case None => builder.value(value)
        }
      case Null =>
        fieldName match {
          case Some(aKey) => builder.nullField(aKey)
          case None => builder.nullValue()
        }
    }
  }
}
object XContentBuilderOps {
  implicit def toXContentBuilderOps(builder: XContentBuilder): XContentBuilderOps = new XContentBuilderOps(builder)
}