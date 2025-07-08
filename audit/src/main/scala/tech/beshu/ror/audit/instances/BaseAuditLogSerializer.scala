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

import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext.*
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditRequestContext, AuditResponseContext}

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters.*
import scala.concurrent.duration.FiniteDuration

object BaseAuditLogSerializer {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("GMT"))

  def serialize(responseContext: AuditResponseContext,
                environmentContext: AuditEnvironmentContext,
                fields: Map[String, AuditValue],
                allowedEventSerializationMode: AllowedEventSerializationMode): Option[JSONObject] = responseContext match {
    case Allowed(requestContext, verbosity, reason) =>
      (verbosity, allowedEventSerializationMode) match {
        case (Verbosity.Error, AllowedEventSerializationMode.SerializeOnlyEventsWithInfoLevelVerbose) =>
          None
        case (Verbosity.Info, AllowedEventSerializationMode.SerializeOnlyEventsWithInfoLevelVerbose) |
             (_, AllowedEventSerializationMode.AlwaysSerialize) =>
          Some(createEntry(matched = true, "ALLOWED", reason, responseContext.duration, requestContext, None))
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

  sealed trait AuditValue

  object AuditValue {

    // Rule
    case object IsMatched extends AuditValue

    case object FinalState extends AuditValue

    case object Reason extends AuditValue

    case object User extends AuditValue

    case object ImpersonatedByUser extends AuditValue

    case object Action extends AuditValue

    case object InvolvedIndices extends AuditValue

    case object AclHistory extends AuditValue

    case object ProcessingDurationMillis extends AuditValue

    // Identifiers
    case object Timestamp extends AuditValue

    case object Id extends AuditValue

    case object CorrelationId extends AuditValue

    case object TaskId extends AuditValue

    // Error details
    case object ErrorType extends AuditValue

    case object ErrorMessage extends AuditValue

    case object Type extends AuditValue

    // HTTP protocol values
    case object HttpMethod extends AuditValue

    case object HttpHeaderNames extends AuditValue

    case object HttpPath extends AuditValue

    case object XForwardedForHttpHeader extends AuditValue

    case object RemoteAddress extends AuditValue

    case object LocalAddress extends AuditValue

    case object Content extends AuditValue

    case object ContentLengthInBytes extends AuditValue

    case object ContentLengthInKb extends AuditValue

    // Environment
    case object EsNodeName extends AuditValue

    case object EsClusterName extends AuditValue

  }

  sealed trait AllowedEventSerializationMode

  object AllowedEventSerializationMode {
    case object SerializeOnlyEventsWithInfoLevelVerbose extends AllowedEventSerializationMode

    case object AlwaysSerialize extends AllowedEventSerializationMode
  }

}
