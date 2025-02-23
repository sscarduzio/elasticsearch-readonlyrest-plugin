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
      .put("match", matched)
      .put("block", reason)
      .put("id", requestContext.id)
      .put("final_state", finalState)
      .put("@timestamp", timestampFormatter.format(requestContext.timestamp))
      .put("correlation_id", requestContext.correlationId)
      .put("processingMillis", duration.toMillis)
      .put("error_type", error.map(_.getClass.getSimpleName).orNull)
      .put("error_message", error.map(_.getMessage).orNull)
      .put("content_len", requestContext.contentLength)
      .put("content_len_kb", requestContext.contentLength / 1024)
      .put("type", requestContext.`type`)
      .put("origin", requestContext.remoteAddress)
      .put("destination", requestContext.localAddress)
      .put("xff", requestContext.requestHeaders.getValue("X-Forwarded-For").flatMap(_.headOption).orNull)
      .put("task_id", requestContext.taskId)
      .put("req_method", requestContext.httpMethod)
      .put("headers", requestContext.requestHeaders.names.asJava)
      .put("path", requestContext.uriPath)
      .put("user", SerializeUser.serialize(requestContext).orNull)
      .put("impersonated_by", requestContext.impersonatedByUserName.orNull)
      .put("action", requestContext.action)
      .put("indices", if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava)
      .put("acl_history", requestContext.history)
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
