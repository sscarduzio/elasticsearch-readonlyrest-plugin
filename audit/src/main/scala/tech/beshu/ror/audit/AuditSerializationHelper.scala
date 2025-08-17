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
package tech.beshu.ror.audit

import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext.*
import tech.beshu.ror.audit.instances.SerializeUser

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters.*
import scala.concurrent.duration.FiniteDuration

private[ror] object AuditSerializationHelper {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("GMT"))

  def serialize(responseContext: AuditResponseContext,
                environmentContext: Option[AuditEnvironmentContext],
                fields: Map[AuditFieldName, AuditFieldValue],
                allowedEventSerializationMode: AllowedEventSerializationMode): Option[JSONObject] = responseContext match {
    case Allowed(requestContext, verbosity, reason) =>
      (verbosity, allowedEventSerializationMode) match {
        case (Verbosity.Error, AllowedEventSerializationMode.SerializeOnlyAllowedEventsWithInfoLevelVerbose) =>
          None
        case (Verbosity.Info, AllowedEventSerializationMode.SerializeOnlyAllowedEventsWithInfoLevelVerbose) |
             (_, AllowedEventSerializationMode.SerializeAllAllowedEvents) =>
          Some(createEntry(fields, matched = true, "ALLOWED", reason, responseContext.duration, requestContext, environmentContext, None))
      }
    case ForbiddenBy(requestContext, _, reason) =>
      Some(createEntry(fields, matched = true, "FORBIDDEN", reason, responseContext.duration, requestContext, environmentContext, None))
    case Forbidden(requestContext) =>
      Some(createEntry(fields, matched = false, "FORBIDDEN", "default", responseContext.duration, requestContext, environmentContext, None))
    case RequestedIndexNotExist(requestContext) =>
      Some(createEntry(fields, matched = false, "INDEX NOT EXIST", "Requested index doesn't exist", responseContext.duration, requestContext, environmentContext, None))
    case Errored(requestContext, cause) =>
      Some(createEntry(fields, matched = false, "ERRORED", "error", responseContext.duration, requestContext, environmentContext, Some(cause)))
  }

  private def createEntry(fields: Map[AuditFieldName, AuditFieldValue],
                          matched: Boolean,
                          finalState: String,
                          reason: String,
                          duration: FiniteDuration,
                          requestContext: AuditRequestContext,
                          environmentContext: Option[AuditEnvironmentContext],
                          error: Option[Throwable]) = {
    val resolvedFields: Map[String, Any] = {
      Map(
        "@timestamp" -> timestampFormatter.format(requestContext.timestamp)
      ) ++ fields.map {
        case (fieldName, fieldValue) =>
          fieldName.value -> resolvePlaceholder(fieldValue, matched, finalState, reason, duration, requestContext, environmentContext, error)
      }
    }
    resolvedFields
      .foldLeft(new JSONObject()) { case (soFar, (key, value)) => soFar.put(key, value) }
      .mergeWith(requestContext.generalAuditEvents)
  }

  private def resolvePlaceholder(auditValue: AuditFieldValue,
                                 matched: Boolean,
                                 finalState: String,
                                 reason: String,
                                 duration: FiniteDuration,
                                 requestContext: AuditRequestContext,
                                 environmentContext: Option[AuditEnvironmentContext],
                                 error: Option[Throwable]): Any = {
    auditValue match {
      case AuditFieldValue.IsMatched => matched
      case AuditFieldValue.FinalState => finalState
      case AuditFieldValue.Reason => reason
      case AuditFieldValue.User => SerializeUser.serialize(requestContext).orNull
      case AuditFieldValue.ImpersonatedByUser => requestContext.impersonatedByUserName.orNull
      case AuditFieldValue.Action => requestContext.action
      case AuditFieldValue.InvolvedIndices => if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava
      case AuditFieldValue.AclHistory => requestContext.history
      case AuditFieldValue.ProcessingDurationMillis => duration.toMillis
      case AuditFieldValue.Timestamp => timestampFormatter.format(requestContext.timestamp)
      case AuditFieldValue.Id => requestContext.id
      case AuditFieldValue.CorrelationId => requestContext.correlationId
      case AuditFieldValue.TaskId => requestContext.taskId
      case AuditFieldValue.ErrorType => error.map(_.getClass.getSimpleName).orNull
      case AuditFieldValue.ErrorMessage => error.map(_.getMessage).orNull
      case AuditFieldValue.Type => requestContext.`type`
      case AuditFieldValue.HttpMethod => requestContext.httpMethod
      case AuditFieldValue.HttpHeaderNames => requestContext.requestHeaders.names.asJava
      case AuditFieldValue.HttpPath => requestContext.uriPath
      case AuditFieldValue.XForwardedForHttpHeader => requestContext.requestHeaders.getValue("X-Forwarded-For").flatMap(_.headOption).orNull
      case AuditFieldValue.RemoteAddress => requestContext.remoteAddress
      case AuditFieldValue.LocalAddress => requestContext.localAddress
      case AuditFieldValue.Content => requestContext.content
      case AuditFieldValue.ContentLengthInBytes => requestContext.contentLength
      case AuditFieldValue.ContentLengthInKb => requestContext.contentLength / 1024
      case AuditFieldValue.EsNodeName => environmentContext.map(_.esNodeName).getOrElse("")
      case AuditFieldValue.EsClusterName => environmentContext.map(_.esClusterName).getOrElse("")
      case AuditFieldValue.StaticText(text) => text
      case AuditFieldValue.Combined(values) => values.map(resolvePlaceholder(_, matched, finalState, reason, duration, requestContext, environmentContext, error)).mkString
    }
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

  sealed trait AllowedEventSerializationMode

  object AllowedEventSerializationMode {
    case object SerializeOnlyAllowedEventsWithInfoLevelVerbose extends AllowedEventSerializationMode

    case object SerializeAllAllowedEvents extends AllowedEventSerializationMode
  }

  final case class AuditFieldName(value: String)

}
