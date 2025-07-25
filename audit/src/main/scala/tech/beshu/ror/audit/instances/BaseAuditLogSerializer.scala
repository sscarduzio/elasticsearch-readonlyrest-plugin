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

import enumeratum._
import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext._
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditRequestContext, AuditResponseContext}

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.collection.immutable.IndexedSeq
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

  private def resolvePlaceholder(auditValue: AuditValuePlaceholder,
                                 environmentContext: AuditEnvironmentContext,
                                 matched: Boolean,
                                 finalState: String,
                                 reason: String,
                                 duration: FiniteDuration,
                                 requestContext: AuditRequestContext,
                                 error: Option[Throwable]): Any = {
    auditValue match {
      case AuditValuePlaceholder.StaticText(text) => text
      case AuditValuePlaceholder.IsMatched => matched
      case AuditValuePlaceholder.FinalState => finalState
      case AuditValuePlaceholder.Reason => reason
      case AuditValuePlaceholder.User => SerializeUser.serialize(requestContext).orNull
      case AuditValuePlaceholder.ImpersonatedByUser => requestContext.impersonatedByUserName.orNull
      case AuditValuePlaceholder.Action => requestContext.action
      case AuditValuePlaceholder.InvolvedIndices => if (requestContext.involvesIndices) requestContext.indices.toList.asJava else List.empty.asJava
      case AuditValuePlaceholder.AclHistory => requestContext.history
      case AuditValuePlaceholder.ProcessingDurationMillis => duration.toMillis
      case AuditValuePlaceholder.Timestamp => timestampFormatter.format(requestContext.timestamp)
      case AuditValuePlaceholder.Id => requestContext.id
      case AuditValuePlaceholder.CorrelationId => requestContext.correlationId
      case AuditValuePlaceholder.TaskId => requestContext.taskId
      case AuditValuePlaceholder.ErrorType => error.map(_.getClass.getSimpleName).orNull
      case AuditValuePlaceholder.ErrorMessage => error.map(_.getMessage).orNull
      case AuditValuePlaceholder.Type => requestContext.`type`
      case AuditValuePlaceholder.HttpMethod => requestContext.httpMethod
      case AuditValuePlaceholder.HttpHeaderNames => requestContext.requestHeaders.names.asJava
      case AuditValuePlaceholder.HttpPath => requestContext.uriPath
      case AuditValuePlaceholder.XForwardedForHttpHeader => requestContext.requestHeaders.getValue("X-Forwarded-For").flatMap(_.headOption).orNull
      case AuditValuePlaceholder.RemoteAddress => requestContext.remoteAddress
      case AuditValuePlaceholder.LocalAddress => requestContext.localAddress
      case AuditValuePlaceholder.Content => requestContext.content
      case AuditValuePlaceholder.ContentLengthInBytes => requestContext.contentLength
      case AuditValuePlaceholder.ContentLengthInKb => requestContext.contentLength / 1024
      case AuditValuePlaceholder.EsNodeName => environmentContext.esNodeName
      case AuditValuePlaceholder.EsClusterName => environmentContext.esClusterName
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

  final case class AuditFieldValue private(value: List[AuditValuePlaceholder])

  object AuditFieldValue {

    private val pattern = "\\{([^}]+)\\}".r

    def apply(placeholder: AuditValuePlaceholder): AuditFieldValue = AuditFieldValue(List(placeholder))

    def fromString(str: String): Either[String, AuditFieldValue] = {
      val matches = pattern.findAllMatchIn(str).toList

      val (parts, missing, lastIndex) =
        matches.foldLeft((List.empty[AuditValuePlaceholder], List.empty[String], 0)) {
          case ((partsAcc, missingAcc, lastEnd), m) =>
            val key = m.group(1)
            val before = str.substring(lastEnd, m.start)
            val partBefore = if (before.nonEmpty) List(AuditValuePlaceholder.StaticText(before)) else Nil

            val (partAfter, newMissing) = AuditValuePlaceholder.withNameOption(key) match {
              case Some(placeholder) => (List(placeholder), Nil)
              case None => (Nil, List(key))
            }

            (partsAcc ++ partBefore ++ partAfter, missingAcc ++ newMissing, m.end)
        }

      val trailing = if (lastIndex < str.length) List(AuditValuePlaceholder.StaticText(str.substring(lastIndex))) else Nil
      val allParts = parts ++ trailing

      missing match {
        case Nil => Right(AuditFieldValue(allParts))
        case missingList => Left(s"There are invalid placeholder values: ${missingList.mkString(", ")}")
      }
    }

  }

  sealed trait AuditValuePlaceholder extends EnumEntry.UpperSnakecase

  object AuditValuePlaceholder extends Enum[AuditValuePlaceholder] {

    // Rule
    case object IsMatched extends AuditValuePlaceholder

    case object FinalState extends AuditValuePlaceholder

    case object Reason extends AuditValuePlaceholder

    case object User extends AuditValuePlaceholder

    case object ImpersonatedByUser extends AuditValuePlaceholder

    case object Action extends AuditValuePlaceholder

    case object InvolvedIndices extends AuditValuePlaceholder

    case object AclHistory extends AuditValuePlaceholder

    case object ProcessingDurationMillis extends AuditValuePlaceholder

    // Identifiers
    case object Timestamp extends AuditValuePlaceholder

    case object Id extends AuditValuePlaceholder

    case object CorrelationId extends AuditValuePlaceholder

    case object TaskId extends AuditValuePlaceholder

    // Error details
    case object ErrorType extends AuditValuePlaceholder

    case object ErrorMessage extends AuditValuePlaceholder

    case object Type extends AuditValuePlaceholder

    // HTTP protocol values
    case object HttpMethod extends AuditValuePlaceholder

    case object HttpHeaderNames extends AuditValuePlaceholder

    case object HttpPath extends AuditValuePlaceholder

    case object XForwardedForHttpHeader extends AuditValuePlaceholder

    case object RemoteAddress extends AuditValuePlaceholder

    case object LocalAddress extends AuditValuePlaceholder

    case object Content extends AuditValuePlaceholder

    case object ContentLengthInBytes extends AuditValuePlaceholder

    case object ContentLengthInKb extends AuditValuePlaceholder

    // Environment
    case object EsNodeName extends AuditValuePlaceholder

    case object EsClusterName extends AuditValuePlaceholder

    final case class StaticText(value: String) extends AuditValuePlaceholder

    override def values: IndexedSeq[AuditValuePlaceholder] = findValues

  }

}
