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
package tech.beshu.ror.audit.utils

import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext._
import tech.beshu.ror.audit.instances.SerializeUser
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditRequestContext, AuditResponseContext}

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

private[ror] object AuditSerializationHelper {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("GMT"))

  def serialize(responseContext: AuditResponseContext,
                environmentContext: Option[AuditEnvironmentContext],
                fields: Map[AuditFieldName, AuditFieldValueDescriptor],
                allowedEventMode: AllowedEventMode): Option[JSONObject] = responseContext match {
    case Allowed(requestContext, verbosity, reason) =>
      allowedEvent(
        allowedEventMode,
        verbosity,
        createEntry(fields, EventData(matched = true, "ALLOWED", reason, responseContext.duration, requestContext, environmentContext, None))
      )
    case ForbiddenBy(requestContext, _, reason) =>
      Some(createEntry(fields, EventData(matched = true, "FORBIDDEN", reason, responseContext.duration, requestContext, environmentContext, None)))
    case Forbidden(requestContext) =>
      Some(createEntry(fields, EventData(matched = false, "FORBIDDEN", "default", responseContext.duration, requestContext, environmentContext, None)))
    case RequestedIndexNotExist(requestContext) =>
      Some(createEntry(fields, EventData(matched = false, "INDEX NOT EXIST", "Requested index doesn't exist", responseContext.duration, requestContext, environmentContext, None)))
    case Errored(requestContext, cause) =>
      Some(createEntry(fields, EventData(matched = false, "ERRORED", "error", responseContext.duration, requestContext, environmentContext, Some(cause))))
  }

  private def allowedEvent(allowedEventMode: AllowedEventMode, verbosity: Verbosity, entry: JSONObject) = {
    allowedEventMode match {
      case AllowedEventMode.IncludeAll =>
        Some(entry)
      case AllowedEventMode.Include(types) if types.contains(verbosity) =>
        Some(entry)
      case _ =>
        None
    }
  }

  private def createEntry(fields: Map[AuditFieldName, AuditFieldValueDescriptor],
                          eventData: EventData) = {
    val resolveAuditFieldValue = resolver(eventData)
    val resolvedFields: Map[String, Any] =
      Map("@timestamp" -> timestampFormatter.format(eventData.requestContext.timestamp)) ++
        fields.map { case (name, valueDescriptor) => name.value -> resolveAuditFieldValue(valueDescriptor) }

    resolvedFields
      .foldLeft(new JSONObject()) { case (soFar, (key, value)) => soFar.put(key, value) }
      .mergeWith(eventData.requestContext.generalAuditEvents)
  }

  private def resolver(eventData: EventData): AuditFieldValueDescriptor => Any = auditValue => {
    val requestContext = eventData.requestContext
    auditValue match {
      case AuditFieldValueDescriptor.IsMatched => eventData.matched
      case AuditFieldValueDescriptor.FinalState => eventData.finalState
      case AuditFieldValueDescriptor.Reason => eventData.reason
      case AuditFieldValueDescriptor.User => SerializeUser.serialize(requestContext).orNull
      case AuditFieldValueDescriptor.ImpersonatedByUser => requestContext.impersonatedByUserName.orNull
      case AuditFieldValueDescriptor.Action => requestContext.action
      case AuditFieldValueDescriptor.InvolvedIndices => if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava
      case AuditFieldValueDescriptor.AclHistory => requestContext.history
      case AuditFieldValueDescriptor.ProcessingDurationMillis => eventData.duration.toMillis
      case AuditFieldValueDescriptor.Timestamp => timestampFormatter.format(requestContext.timestamp)
      case AuditFieldValueDescriptor.Id => requestContext.id
      case AuditFieldValueDescriptor.CorrelationId => requestContext.correlationId
      case AuditFieldValueDescriptor.TaskId => requestContext.taskId
      case AuditFieldValueDescriptor.ErrorType => eventData.error.map(_.getClass.getSimpleName).orNull
      case AuditFieldValueDescriptor.ErrorMessage => eventData.error.map(_.getMessage).orNull
      case AuditFieldValueDescriptor.Type => requestContext.`type`
      case AuditFieldValueDescriptor.HttpMethod => requestContext.httpMethod
      case AuditFieldValueDescriptor.HttpHeaderNames => requestContext.requestHeaders.names.asJava
      case AuditFieldValueDescriptor.HttpPath => requestContext.uriPath
      case AuditFieldValueDescriptor.XForwardedForHttpHeader => requestContext.requestHeaders.getValue("X-Forwarded-For").flatMap(_.headOption).orNull
      case AuditFieldValueDescriptor.RemoteAddress => requestContext.remoteAddress
      case AuditFieldValueDescriptor.LocalAddress => requestContext.localAddress
      case AuditFieldValueDescriptor.Content => requestContext.content
      case AuditFieldValueDescriptor.ContentLengthInBytes => requestContext.contentLength
      case AuditFieldValueDescriptor.ContentLengthInKb => requestContext.contentLength / 1024
      case AuditFieldValueDescriptor.EsNodeName => eventData.environmentContext.map(_.esNodeName).getOrElse("")
      case AuditFieldValueDescriptor.EsClusterName => eventData.environmentContext.map(_.esClusterName).getOrElse("")
      case AuditFieldValueDescriptor.StaticText(text) => text
      case AuditFieldValueDescriptor.Combined(values) => values.map(resolver(eventData)).mkString
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

  private final case class EventData(matched: Boolean,
                                     finalState: String,
                                     reason: String,
                                     duration: FiniteDuration,
                                     requestContext: AuditRequestContext,
                                     environmentContext: Option[AuditEnvironmentContext],
                                     error: Option[Throwable])

  sealed trait AllowedEventMode

  object AllowedEventMode {
    case object IncludeAll extends AllowedEventMode

    final case class Include(types: Set[Verbosity]) extends AllowedEventMode
  }

  final case class AuditFieldName(value: String)

  sealed trait AuditFieldValueDescriptor

  object AuditFieldValueDescriptor {

    // Rule
    case object IsMatched extends AuditFieldValueDescriptor

    case object FinalState extends AuditFieldValueDescriptor

    case object Reason extends AuditFieldValueDescriptor

    case object User extends AuditFieldValueDescriptor

    case object ImpersonatedByUser extends AuditFieldValueDescriptor

    case object Action extends AuditFieldValueDescriptor

    case object InvolvedIndices extends AuditFieldValueDescriptor

    case object AclHistory extends AuditFieldValueDescriptor

    case object ProcessingDurationMillis extends AuditFieldValueDescriptor

    // Identifiers
    case object Timestamp extends AuditFieldValueDescriptor

    case object Id extends AuditFieldValueDescriptor

    case object CorrelationId extends AuditFieldValueDescriptor

    case object TaskId extends AuditFieldValueDescriptor

    // Error details
    case object ErrorType extends AuditFieldValueDescriptor

    case object ErrorMessage extends AuditFieldValueDescriptor

    case object Type extends AuditFieldValueDescriptor

    // HTTP protocol values
    case object HttpMethod extends AuditFieldValueDescriptor

    case object HttpHeaderNames extends AuditFieldValueDescriptor

    case object HttpPath extends AuditFieldValueDescriptor

    case object XForwardedForHttpHeader extends AuditFieldValueDescriptor

    case object RemoteAddress extends AuditFieldValueDescriptor

    case object LocalAddress extends AuditFieldValueDescriptor

    case object Content extends AuditFieldValueDescriptor

    case object ContentLengthInBytes extends AuditFieldValueDescriptor

    case object ContentLengthInKb extends AuditFieldValueDescriptor

    // ES environment

    case object EsNodeName extends AuditFieldValueDescriptor

    case object EsClusterName extends AuditFieldValueDescriptor

    // Technical

    final case class StaticText(value: String) extends AuditFieldValueDescriptor

    final case class Combined(values: List[AuditFieldValueDescriptor]) extends AuditFieldValueDescriptor

  }

}
