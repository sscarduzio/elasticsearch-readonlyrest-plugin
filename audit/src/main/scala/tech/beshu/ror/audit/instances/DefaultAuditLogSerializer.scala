package tech.beshu.ror.audit.instances

import java.time.format.DateTimeFormatter

import tech.beshu.ror.audit.AuditResponseContext._
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}

import scala.concurrent.duration.FiniteDuration

class DefaultAuditLogSerializer extends AuditLogSerializer {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

  override def onResponse(responseContext: AuditResponseContext): Option[Map[String, String]] = responseContext match {
    case Allowed(_, verbosity, _) if verbosity == Verbosity.Info =>
      None
    case Allowed(requestContext, _, reason) =>
      Some(createEntry(matched = true, "ALLOWED", reason, responseContext.duration, requestContext, None))
    case ForbiddenBy(requestContext, _, reason) =>
      Some(createEntry(matched = true, "FORBIDDEN", reason, responseContext.duration, requestContext, None))
    case Forbidden(requestContext) =>
      Some(createEntry(matched = false, "FORBIDDEN", "default", responseContext.duration, requestContext, None))
    case Errored(requestContext, cause) =>
      Some(createEntry(matched = false, "ERRORED", "error", responseContext.duration, requestContext, Some(cause)))
    case NotFound(requestContext, cause) =>
      Some(createEntry(matched = false, "NOT_FOUND", "error", responseContext.duration, requestContext, Some(cause)))
  }

  private def createEntry(matched: Boolean,
                          finalState: String,
                          reason: String,
                          duration: FiniteDuration,
                          requestContext: AuditRequestContext,
                          error: Option[Throwable]) = {
    val requiredFields = Map(
      "match" -> matched.toString,
      "block" -> reason,
      "id" -> requestContext.id,
      "final_state" -> finalState,
      "@timestamp" -> timestampFormatter.format(requestContext.timestamp),
      "processingMillis" -> duration.toMillis.toString,
      "content_len" -> requestContext.contentLength.toString,
      "content_len_kb" -> (requestContext.contentLength / 1024).toString,
      "type" -> requestContext.`type`,
      "origin" -> requestContext.remoteAddress,
      "destination" -> requestContext.localAddress,
      "task_id" -> requestContext.taskId.toString,
      "req_method" -> requestContext.httpMethod,
      "headers" -> requestContext.headers.keys.mkString(", "),
      "path" -> requestContext.uriPath,
      "action" -> requestContext.action,
      "indices" -> (if (requestContext.involvesIndices) requestContext.indices.mkString(", ") else ""),
      "acl_history" -> requestContext.history
    )
    val optionalFields =
      requestContext.headers.get("X-Forwarded-For").map("xff" -> _) ::
        requestContext.loggedInUserName.map("user" -> _) ::
        error.map(_.getClass.getSimpleName).map("error_type" -> _) ::
        error.map(_.getMessage).map("error_message" -> _) ::
        Nil

    requiredFields ++ optionalFields.flatten.toMap
  }
}
