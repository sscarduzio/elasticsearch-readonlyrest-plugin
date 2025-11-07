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
import tech.beshu.ror.audit.{AuditRequestContext, AuditResponseContext}

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

private[ror] object AuditSerializationHelper {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("GMT"))

  def serialize(responseContext: AuditResponseContext,
                fieldGroups: Set[AuditFieldGroup],
                allowedEventMode: AllowedEventMode): Option[JSONObject] = {
    val fields = fieldGroups.flatMap {
      case AuditFieldGroup.CommonFields => commonFields
      case AuditFieldGroup.EsEnvironmentFields => esEnvironmentFields
      case AuditFieldGroup.FullRequestContentFields => requestContentFields
    }.toMap
    serialize(
      responseContext = responseContext,
      fields = fields,
      allowedEventMode = allowedEventMode
    )
  }

  def serialize(responseContext: AuditResponseContext,
                fields: Map[AuditFieldPath, AuditFieldValueDescriptor],
                allowedEventMode: AllowedEventMode): Option[JSONObject] = {
    responseContext match {
      case Allowed(requestContext, verbosity, reason) =>
        allowedEvent(
          allowedEventMode,
          verbosity,
          createEntry(fields, EventData(matched = true, FinalState.Allowed, reason, responseContext.duration, requestContext, None))
        )
      case ForbiddenBy(requestContext, _, reason) =>
        Some(createEntry(fields, EventData(matched = true, FinalState.Forbidden, reason, responseContext.duration, requestContext, None)))
      case Forbidden(requestContext) =>
        Some(createEntry(fields, EventData(matched = false, FinalState.Forbidden, "default", responseContext.duration, requestContext, None)))
      case RequestedIndexNotExist(requestContext) =>
        Some(createEntry(fields, EventData(matched = false, FinalState.IndexNotExist, "Requested index doesn't exist", responseContext.duration, requestContext, None)))
      case Errored(requestContext, cause) =>
        Some(createEntry(fields, EventData(matched = false, FinalState.Errored, "error", responseContext.duration, requestContext, Some(cause))))
    }
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

  private def createEntry(fields: Map[AuditFieldPath, AuditFieldValueDescriptor],
                          eventData: EventData) = {
    val resolveAuditFieldValue = resolver(eventData)
    val resolvedFields: Map[AuditFieldPath, Any] =
      Map(AuditFieldPath("@timestamp") -> timestampFormatter.format(eventData.requestContext.timestamp)) ++
        fields.map { case (name, valueDescriptor) => name -> resolveAuditFieldValue(valueDescriptor) }

    resolvedFields.foldLeft(new JSONObject()) { case (soFar, (path, value)) =>
      putNested(soFar, path.path.toList, value)
    }.mergeWith(eventData.requestContext.generalAuditEvents)
  }

  private def putNested(json: JSONObject, path: List[String], value: Any): JSONObject = {
    path match {
      case Nil =>
        json
      case key :: Nil =>
        json.put(key, value)
        json
      case key :: tail =>
        val child = Option(json.optJSONObject(key)).getOrElse(new JSONObject())
        json.put(key, putNested(child, tail, value))
        json
    }
  }

  private def resolver(eventData: EventData): AuditFieldValueDescriptor => Any = auditValue => {
    val requestContext = eventData.requestContext
    auditValue match {
      case AuditFieldValueDescriptor.IsMatched => eventData.matched
      case AuditFieldValueDescriptor.FinalState => eventData.finalState match {
        case FinalState.Allowed => "ALLOWED"
        case FinalState.Forbidden => "FORBIDDEN"
        case FinalState.Errored => "ERRORED"
        case FinalState.IndexNotExist => "INDEX NOT EXIST"
      }
      case AuditFieldValueDescriptor.EcsEventOutcome => eventData.finalState match {
        case FinalState.Allowed => "success"
        case FinalState.Forbidden => "failure"
        case FinalState.Errored => "unknown"
        case FinalState.IndexNotExist => "unknown"
      }
      case AuditFieldValueDescriptor.Reason => eventData.reason
      case AuditFieldValueDescriptor.User => SerializeUser.serialize(requestContext).orNull
      case AuditFieldValueDescriptor.LoggedUser => requestContext.loggedInUserName.orNull
      case AuditFieldValueDescriptor.PresentedIdentity => requestContext.attemptedUserName.orNull
      case AuditFieldValueDescriptor.ImpersonatedByUser => requestContext.impersonatedByUserName.orNull
      case AuditFieldValueDescriptor.Action => requestContext.action
      case AuditFieldValueDescriptor.InvolvedIndices => if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava
      case AuditFieldValueDescriptor.AclHistory => requestContext.history
      case AuditFieldValueDescriptor.ProcessingDurationMillis => eventData.duration.toMillis
      case AuditFieldValueDescriptor.ProcessingDurationNanos => eventData.duration.toNanos
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
      case AuditFieldValueDescriptor.EsNodeName => eventData.requestContext.auditEnvironmentContext.esNodeName
      case AuditFieldValueDescriptor.EsClusterName => eventData.requestContext.auditEnvironmentContext.esClusterName
      case AuditFieldValueDescriptor.StaticText(text) => text
      case AuditFieldValueDescriptor.NumericValue(value) => value.bigDecimal
      case AuditFieldValueDescriptor.BooleanValue(value) => value
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
                                     finalState: FinalState,
                                     reason: String,
                                     duration: FiniteDuration,
                                     requestContext: AuditRequestContext,
                                     error: Option[Throwable])

  private sealed trait FinalState

  private object FinalState {
    case object Allowed extends FinalState

    case object Forbidden extends FinalState

    case object Errored extends FinalState

    case object IndexNotExist extends FinalState
  }

  sealed trait AllowedEventMode

  object AllowedEventMode {
    case object IncludeAll extends AllowedEventMode

    final case class Include(types: Set[Verbosity]) extends AllowedEventMode
  }

  final case class AuditFieldPath(path: NonEmptyList[String])

  object AuditFieldPath {
    def apply(name: String): AuditFieldPath =
      AuditFieldPath(NonEmptyList.one(name))

    def apply(head: String, tail: String*): AuditFieldPath =
      AuditFieldPath(NonEmptyList(head, tail.toList))

    def fields(values: ((AuditFieldPath, AuditFieldValueDescriptor) | Map[AuditFieldPath, AuditFieldValueDescriptor])*): Map[AuditFieldPath, AuditFieldValueDescriptor] =
      values.flatMap(toMap).toMap

    def withPrefix(prefix: String)(
      values: ((AuditFieldPath, AuditFieldValueDescriptor) | Map[AuditFieldPath, AuditFieldValueDescriptor])*
    ): Map[AuditFieldPath, AuditFieldValueDescriptor] =
      withPrefix(prefix, values.flatMap(toMap).toMap)

    private def withPrefix(prefix: String,
                           values: Map[AuditFieldPath, AuditFieldValueDescriptor]): Map[AuditFieldPath, AuditFieldValueDescriptor] = {
      val prefixNel = NonEmptyList.one(prefix)
      values.map { case (path, desc) =>
        val newPath = AuditFieldPath(prefixNel.concatNel(path.path))
        newPath -> desc
      }
    }

    private def toMap(value: (AuditFieldPath, AuditFieldValueDescriptor) | Map[AuditFieldPath, AuditFieldValueDescriptor]): Map[AuditFieldPath, AuditFieldValueDescriptor] = {
      value match {
        case (path: AuditFieldPath, value: AuditFieldValueDescriptor) =>
          Map(path -> value)
        case values: Map[AuditFieldPath, AuditFieldValueDescriptor] =>
          values
      }
    }
  }

  sealed trait AuditFieldValueDescriptor

  object AuditFieldValueDescriptor {

    // Rule
    case object IsMatched extends AuditFieldValueDescriptor

    case object FinalState extends AuditFieldValueDescriptor

    case object EcsEventOutcome extends AuditFieldValueDescriptor

    case object Reason extends AuditFieldValueDescriptor

    @deprecated("[ROR] The User audit field value descriptor should not be used. Use LoggedUser or PresentedIdentity instead", "1.68.0")
    case object User extends AuditFieldValueDescriptor

    case object LoggedUser extends AuditFieldValueDescriptor

    case object PresentedIdentity extends AuditFieldValueDescriptor

    case object ImpersonatedByUser extends AuditFieldValueDescriptor

    case object Action extends AuditFieldValueDescriptor

    case object InvolvedIndices extends AuditFieldValueDescriptor

    case object AclHistory extends AuditFieldValueDescriptor

    case object ProcessingDurationMillis extends AuditFieldValueDescriptor

    case object ProcessingDurationNanos extends AuditFieldValueDescriptor

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

    final case class BooleanValue(value: Boolean) extends AuditFieldValueDescriptor

    final case class NumericValue(value: BigDecimal) extends AuditFieldValueDescriptor

    final case class Combined(values: List[AuditFieldValueDescriptor]) extends AuditFieldValueDescriptor

  }

  sealed trait AuditFieldGroup

  object AuditFieldGroup {
    case object CommonFields extends AuditFieldGroup

    case object EsEnvironmentFields extends AuditFieldGroup

    case object FullRequestContentFields extends AuditFieldGroup
  }

  private val commonFields: Map[AuditFieldPath, AuditFieldValueDescriptor] = Map(
    AuditFieldPath("match") -> AuditFieldValueDescriptor.IsMatched,
    AuditFieldPath("block") -> AuditFieldValueDescriptor.Reason,
    AuditFieldPath("id") -> AuditFieldValueDescriptor.Id,
    AuditFieldPath("final_state") -> AuditFieldValueDescriptor.FinalState,
    AuditFieldPath("@timestamp") -> AuditFieldValueDescriptor.Timestamp,
    AuditFieldPath("correlation_id") -> AuditFieldValueDescriptor.CorrelationId,
    AuditFieldPath("processingMillis") -> AuditFieldValueDescriptor.ProcessingDurationMillis,
    AuditFieldPath("error_type") -> AuditFieldValueDescriptor.ErrorType,
    AuditFieldPath("error_message") -> AuditFieldValueDescriptor.ErrorMessage,
    AuditFieldPath("content_len") -> AuditFieldValueDescriptor.ContentLengthInBytes,
    AuditFieldPath("content_len_kb") -> AuditFieldValueDescriptor.ContentLengthInKb,
    AuditFieldPath("type") -> AuditFieldValueDescriptor.Type,
    AuditFieldPath("origin") -> AuditFieldValueDescriptor.RemoteAddress,
    AuditFieldPath("destination") -> AuditFieldValueDescriptor.LocalAddress,
    AuditFieldPath("xff") -> AuditFieldValueDescriptor.XForwardedForHttpHeader,
    AuditFieldPath("task_id") -> AuditFieldValueDescriptor.TaskId,
    AuditFieldPath("req_method") -> AuditFieldValueDescriptor.HttpMethod,
    AuditFieldPath("headers") -> AuditFieldValueDescriptor.HttpHeaderNames,
    AuditFieldPath("path") -> AuditFieldValueDescriptor.HttpPath,
    AuditFieldPath("user") -> AuditFieldValueDescriptor.User,
    AuditFieldPath("logged_user") -> AuditFieldValueDescriptor.LoggedUser,
    AuditFieldPath("presented_identity") -> AuditFieldValueDescriptor.PresentedIdentity,
    AuditFieldPath("impersonated_by") -> AuditFieldValueDescriptor.ImpersonatedByUser,
    AuditFieldPath("action") -> AuditFieldValueDescriptor.Action,
    AuditFieldPath("indices") -> AuditFieldValueDescriptor.InvolvedIndices,
    AuditFieldPath("acl_history") -> AuditFieldValueDescriptor.AclHistory
  )

  private val esEnvironmentFields: Map[AuditFieldPath, AuditFieldValueDescriptor] = Map(
    AuditFieldPath("es_node_name") -> AuditFieldValueDescriptor.EsNodeName,
    AuditFieldPath("es_cluster_name") -> AuditFieldValueDescriptor.EsClusterName
  )

  private val requestContentFields: Map[AuditFieldPath, AuditFieldValueDescriptor] = Map(
    AuditFieldPath("content") -> AuditFieldValueDescriptor.Content
  )

}
