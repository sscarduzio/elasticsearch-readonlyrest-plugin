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

import enumeratum.*
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

  private def createEntry(fields: Map[String, AuditValue],
                          environmentContext: AuditEnvironmentContext,
                          matched: Boolean,
                          finalState: String,
                          reason: String,
                          duration: FiniteDuration,
                          requestContext: AuditRequestContext,
                          error: Option[Throwable]) = {
    val resolvedFields: Map[String, Any] = fields.view.mapValues {
      case AuditValue.IsMatched => matched
      case AuditValue.FinalState => finalState
      case AuditValue.Reason => reason
      case AuditValue.User => SerializeUser.serialize(requestContext).orNull
      case AuditValue.ImpersonatedByUser => requestContext.impersonatedByUserName.orNull
      case AuditValue.Action => requestContext.action
      case AuditValue.InvolvedIndices => if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava
      case AuditValue.AclHistory => requestContext.history
      case AuditValue.ProcessingDurationMillis => duration.toMillis
      case AuditValue.Timestamp => timestampFormatter.format(requestContext.timestamp)
      case AuditValue.Id => requestContext.id
      case AuditValue.CorrelationId => requestContext.correlationId
      case AuditValue.TaskId => requestContext.taskId
      case AuditValue.ErrorType => error.map(_.getClass.getSimpleName).orNull
      case AuditValue.ErrorMessage => error.map(_.getMessage).orNull
      case AuditValue.Type => requestContext.`type`
      case AuditValue.HttpMethod => requestContext.httpMethod
      case AuditValue.HttpHeaderNames => requestContext.requestHeaders.names.asJava
      case AuditValue.HttpPath => requestContext.uriPath
      case AuditValue.XForwardedForHttpHeader => requestContext.requestHeaders.getValue("X-Forwarded-For").flatMap(_.headOption).orNull
      case AuditValue.RemoteAddress => requestContext.remoteAddress
      case AuditValue.LocalAddress => requestContext.localAddress
      case AuditValue.Content => requestContext.content
      case AuditValue.ContentLengthInBytes => requestContext.contentLength
      case AuditValue.ContentLengthInKb => requestContext.contentLength / 1024
      case AuditValue.EsNodeName => environmentContext.esNodeName
      case AuditValue.EsClusterName => environmentContext.esClusterName
    }.toMap ++ Map(
      "@timestamp" -> timestampFormatter.format(requestContext.timestamp),
    )
    resolvedFields
      .foldLeft(new JSONObject()) { case (soFar, (key, value)) => soFar.put(key, value) }
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

  final case class Fields(value: Map[String, AuditValue])

  sealed trait AuditValue extends EnumEntry.UpperSnakecase

  object AuditValue extends Enum[AuditValue] {

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

    override def values: IndexedSeq[AuditValue] = findValues

  }

  sealed trait AllowedEventSerializationMode

  object AllowedEventSerializationMode {
    case object SerializeOnlyEventsWithInfoLevelVerbose extends AllowedEventSerializationMode

    case object AlwaysSerialize extends AllowedEventSerializationMode
  }

}
