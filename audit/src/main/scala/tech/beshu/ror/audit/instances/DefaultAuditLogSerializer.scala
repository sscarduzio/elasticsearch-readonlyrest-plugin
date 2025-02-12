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
package tech.beshu.ror.audit.instances

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext._
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer.Keys
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

class DefaultAuditLogSerializer extends AuditLogSerializer {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("GMT"))

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = responseContext match {
    case Allowed(requestContext, verbosity, reason) =>
      verbosity match {
        case Verbosity.Info =>
          Some(createEntry(matched = true, "ALLOWED", reason, responseContext.duration, requestContext, None))
        case Verbosity.Error =>
          None
      }
    case ForbiddenBy(requestContext, _, reason) =>
      Some(createEntry(matched = true, "FORBIDDEN", reason, responseContext.duration, requestContext, None))
    case Forbidden(requestContext) =>
      Some(createEntry(matched = false, "FORBIDDEN", "default", responseContext.duration, requestContext, None))
    case RequestedIndexNotExist(requestContext) =>
      Some(createEntry(matched = false, "INDEX NOT EXIST", "Requested index doesn't exist", responseContext.duration, requestContext, None))
    case Errored(requestContext, cause) =>
      Some(createEntry(matched = false, "ERRORED", "error", responseContext.duration, requestContext, Some(cause)))
  }

  private def createEntry(matched: Boolean,
                          finalState: String,
                          reason: String,
                          duration: FiniteDuration,
                          requestContext: AuditRequestContext,
                          error: Option[Throwable]) = {
    new JSONObject()
      .put(Keys.`match`, matched)
      .put(Keys.block, reason)
      .put(Keys.id, requestContext.id)
      .put(Keys.finalState, finalState)
      .put(Keys.timestamp, timestampFormatter.format(requestContext.timestamp))
      .put(Keys.correlationId, requestContext.correlationId)
      .put(Keys.processingMillis, duration.toMillis)
      .put(Keys.errorType, error.map(_.getClass.getSimpleName).orNull)
      .put(Keys.errorMessage, error.map(_.getMessage).orNull)
      .put(Keys.contentLength, requestContext.contentLength)
      .put(Keys.contentLengthKb, requestContext.contentLength / 1024)
      .put(Keys.`type`, requestContext.`type`)
      .put(Keys.origin, requestContext.remoteAddress)
      .put(Keys.destination, requestContext.localAddress)
      .put(Keys.xforwarderFor, requestContext.requestHeaders.getValue("X-Forwarded-For").flatMap(_.headOption).orNull)
      .put(Keys.taskId, requestContext.taskId)
      .put(Keys.requestMethod, requestContext.httpMethod)
      .put(Keys.headers, requestContext.requestHeaders.names.asJava)
      .put(Keys.path, requestContext.uriPath)
      .put(Keys.user, SerializeUser.serialize(requestContext).orNull)
      .put(Keys.impersonatedBy, requestContext.impersonatedByUserName.orNull)
      .put(Keys.action, requestContext.action)
      .put(Keys.indices, if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava)
      .put(Keys.aclHistory, requestContext.history)
      .mergeWith(requestContext.generalAuditEvents)
  }

  private implicit class JsonObjectOps(val mainJson: JSONObject) {
    def mergeWith(secondaryJson: JSONObject): JSONObject = {
      jsonKeys(secondaryJson).foldLeft(mainJson) {
        case (json, name) if !json.has(name) =>
          json.put(name, secondaryJson.get(name))
        case (json, _) =>
          json
      }
    }

    private def jsonKeys(json: JSONObject) = {
      Option(JSONObject.getNames(json)).toList.flatten
    }
  }
}

private[ror] object DefaultAuditLogSerializer {

  private[DefaultAuditLogSerializer] object Keys {
    val `match` = "match"
    val block = "block"
    val id = "id"
    val finalState = "final_state"
    val timestamp = "@timestamp"
    val correlationId = "correlation_id"
    val processingMillis = "processingMillis"
    val errorType = "error_type"
    val errorMessage = "error_message"
    val contentLength = "content_len"
    val contentLengthKb = "content_len_kb"
    val `type` = "type"
    val origin = "origin"
    val destination = "destination"
    val xforwarderFor = "xff"
    val taskId = "task_id"
    val requestMethod = "req_method"
    val headers = "headers"
    val path = "path"
    val user = "user"
    val impersonatedBy = "impersonated_by"
    val action = "action"
    val indices = "indices"
    val aclHistory = "acl_history"
  }

  import Keys._
  import FieldType._

  val defaultIndexedMappings: Map[String, FieldType] = Map(
    `match` -> Bool,
    block -> Str,
    id -> Str,
    finalState -> Str,
    timestamp -> Date,
    correlationId -> Str,
    user -> Str,
    action -> Str
  )

}
