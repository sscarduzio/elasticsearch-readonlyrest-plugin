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

import java.util.function.Consumer

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.{RestChannel, RestStatus}
import ujson.{Arr, Bool, Null, Num, Obj, Str, Value}

object ContentBuilderHelper {

  def createErrorResponse(channel: RestChannel,
                          status: RestStatus,
                          rootCause: Consumer[XContentBuilder]): XContentBuilder = {
    val builder = channel.newErrorBuilder.startObject
    builder.startObject("error")
    builder.startArray("root_cause")
    builder.startObject
    rootCause.accept(builder)
    builder.endObject

    builder.endArray

    rootCause.accept(builder)
    builder.field("status", status.getStatus)
    builder.endObject

    builder.endObject
  }

  implicit class XContentBuilderOps(val builder: XContentBuilder) extends AnyVal {
    def add(json: Value): XContentBuilder = buildJsonContent(builder, None, json)

    private def buildJsonContent(builder: XContentBuilder, key: Option[String], json: Value): XContentBuilder = {
      def startObject(currentBuilder: XContentBuilder) = key match {
        case Some(name) => currentBuilder.startObject(name)
        case None => currentBuilder.startObject()
      }
      def startArray(currentBuilder: XContentBuilder) = key match {
        case Some(name) => currentBuilder.startArray(name)
        case None => currentBuilder.startArray()
      }
      json match {
        case Obj(map) =>
          map
            .foldLeft(startObject(builder)) {
              case (currentBuilder, (fieldName, fieldValue)) =>
                buildJsonContent(currentBuilder, Some(fieldName), fieldValue)
            }
            .endObject()
        case Arr(values) =>
          values
            .foldLeft(startArray(builder)) {
              case (currentBuilder, arrayObject) =>
                buildJsonContent(currentBuilder, None, arrayObject)
            }
            .endArray()
        case Str(value) =>
          key match {
            case Some(aKey) => builder.field(aKey, value)
            case None => builder.value(value)
          }
        case Num(value) =>
          key match {
            case Some(aKey) => builder.field(aKey, value)
            case None => builder.value(value)
          }
        case Bool(value) =>
          key match {
            case Some(aKey) => builder.field(aKey, value)
            case None => builder.value(value)
          }
        case Null =>
          key match {
            case Some(aKey) => builder.nullField(aKey)
            case None => builder.nullValue()
          }
      }
    }
  }
}
