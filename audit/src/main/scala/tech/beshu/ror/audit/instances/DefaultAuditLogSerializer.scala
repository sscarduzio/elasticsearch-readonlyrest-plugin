package tech.beshu.ror.audit.instances

import java.time.format.DateTimeFormatter

import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext._
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}

import scala.concurrent.duration.FiniteDuration

class DefaultAuditLogSerializer extends AuditLogSerializer {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = responseContext match {
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
      Some(createEntry(matched = false, "NOT_FOUND", "not found", responseContext.duration, requestContext, Some(cause)))
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
      .put("processingMillis", duration.toMillis)
      .put("error_type", error.map(_.getClass.getSimpleName).orNull)
      .put("error_message", error.map(_.getMessage).orNull)
      .put("content_len", requestContext.contentLength)
      .put("content_len_kb", requestContext.contentLength / 1024)
      .put("type", requestContext.`type`)
      .put("origin", requestContext.remoteAddress)
      .put("destination", requestContext.localAddress)
      .put("xff", requestContext.headers.get("X-Forwarded-For").orNull)
      .put("task_id", requestContext.taskId.toString)
      .put("req_method", requestContext.httpMethod)
      .put("headers", requestContext.headers.keys)
      .put("path", requestContext.uriPath)
      .put("user", requestContext.loggedInUserName.orNull)
      .put("action", requestContext.action)
      .put("indices", if (requestContext.involvesIndices) requestContext.indices else Set.empty)
      .put("acl_history", requestContext.history)
  }
}
