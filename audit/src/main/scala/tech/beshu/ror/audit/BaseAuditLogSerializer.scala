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
import tech.beshu.ror.audit.AuditResponseContext._
import tech.beshu.ror.audit.instances.SerializeUser

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

object BaseAuditLogSerializer {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("GMT"))

  def serialize(responseContext: AuditResponseContext,
                environmentContext: AuditEnvironmentContext,
                fields: Map[String, AuditFieldValue],
                allowedEventSerializationMode: AllowedEventSerializationMode): Option[JSONObject] = responseContext match {
    case Allowed(requestContext, verbosity, reason) =>
      (verbosity, allowedEventSerializationMode) match {
        case (Verbosity.Error, AllowedEventSerializationMode.SerializeOnlyAllowedEventsWithInfoLevelVerbose) =>
          None
        case (Verbosity.Info, AllowedEventSerializationMode.SerializeOnlyAllowedEventsWithInfoLevelVerbose) |
             (_, AllowedEventSerializationMode.SerializeAllAllowedEvents) =>
          Some(createEntry(fields, environmentContext, matched = true, "ALLOWED", reason, responseContext.duration, requestContext, None))
      }
    case ForbiddenBy(requestContext, _, reason) =>
      Some(createEntry(fields, environmentContext, matched = true, "FORBIDDEN", reason, responseContext.duration, requestContext, None))
    case Forbidden(requestContext) =>
      Some(createEntry(fields, environmentContext, matched = false, "FORBIDDEN", "default", responseContext.duration, requestContext, None))
    case RequestedIndexNotExist(requestContext) =>
      Some(createEntry(fields, environmentContext, matched = false, "INDEX NOT EXIST", "Requested index doesn't exist", responseContext.duration, requestContext, None))
    case Errored(requestContext, cause) =>
      Some(createEntry(fields, environmentContext, matched = false, "ERRORED", "error", responseContext.duration, requestContext, Some(cause)))
  }

  private def createEntry(fields: Map[String, AuditFieldValue],
                          environmentContext: AuditEnvironmentContext,
                          matched: Boolean,
                          finalState: String,
                          reason: String,
                          duration: FiniteDuration,
                          requestContext: AuditRequestContext,
                          error: Option[Throwable]) = {
    val resolvedFields: Map[String, Any] = {
      Map(
        "@timestamp" -> timestampFormatter.format(requestContext.timestamp)
      ) ++ fields.map {
        case (key, field) =>
          val resolvedValue = field.value.map {
            resolvePlaceholder(_, environmentContext, matched, finalState, reason, duration, requestContext, error)
          } match {
            case Nil => ""
            case singleElement :: Nil => singleElement
            case multipleElements => multipleElements.mkString
          }
          key -> resolvedValue
      }
    }
    resolvedFields
      .foldLeft(new JSONObject()) { case (soFar, (key, value)) => soFar.put(key, value) }
      .mergeWith(requestContext.generalAuditEvents)
  }

  private def resolvePlaceholder(auditValue: AuditFieldValuePlaceholder,
                                 environmentContext: AuditEnvironmentContext,
                                 matched: Boolean,
                                 finalState: String,
                                 reason: String,
                                 duration: FiniteDuration,
                                 requestContext: AuditRequestContext,
                                 error: Option[Throwable]): Any = {
    auditValue match {
      case AuditFieldValuePlaceholder.StaticText(text) => text
      case AuditFieldValuePlaceholder.IsMatched => matched
      case AuditFieldValuePlaceholder.FinalState => finalState
      case AuditFieldValuePlaceholder.Reason => reason
      case AuditFieldValuePlaceholder.User => SerializeUser.serialize(requestContext).orNull
      case AuditFieldValuePlaceholder.ImpersonatedByUser => requestContext.impersonatedByUserName.orNull
      case AuditFieldValuePlaceholder.Action => requestContext.action
      case AuditFieldValuePlaceholder.InvolvedIndices => if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava
      case AuditFieldValuePlaceholder.AclHistory => requestContext.history
      case AuditFieldValuePlaceholder.ProcessingDurationMillis => duration.toMillis
      case AuditFieldValuePlaceholder.Timestamp => timestampFormatter.format(requestContext.timestamp)
      case AuditFieldValuePlaceholder.Id => requestContext.id
      case AuditFieldValuePlaceholder.CorrelationId => requestContext.correlationId
      case AuditFieldValuePlaceholder.TaskId => requestContext.taskId
      case AuditFieldValuePlaceholder.ErrorType => error.map(_.getClass.getSimpleName).orNull
      case AuditFieldValuePlaceholder.ErrorMessage => error.map(_.getMessage).orNull
      case AuditFieldValuePlaceholder.Type => requestContext.`type`
      case AuditFieldValuePlaceholder.HttpMethod => requestContext.httpMethod
      case AuditFieldValuePlaceholder.HttpHeaderNames => requestContext.requestHeaders.names.asJava
      case AuditFieldValuePlaceholder.HttpPath => requestContext.uriPath
      case AuditFieldValuePlaceholder.XForwardedForHttpHeader => requestContext.requestHeaders.getValue("X-Forwarded-For").flatMap(_.headOption).orNull
      case AuditFieldValuePlaceholder.RemoteAddress => requestContext.remoteAddress
      case AuditFieldValuePlaceholder.LocalAddress => requestContext.localAddress
      case AuditFieldValuePlaceholder.Content => requestContext.content
      case AuditFieldValuePlaceholder.ContentLengthInBytes => requestContext.contentLength
      case AuditFieldValuePlaceholder.ContentLengthInKb => requestContext.contentLength / 1024
      case AuditFieldValuePlaceholder.EsNodeName => environmentContext.esNodeName
      case AuditFieldValuePlaceholder.EsClusterName => environmentContext.esClusterName
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

  final case class AuditFields(value: Map[String, AuditFieldValue])

}
