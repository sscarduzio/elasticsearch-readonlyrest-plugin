package tech.beshu.ror.audit.adapters

import java.{lang, util}
import java.util.{Date, Optional}

import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext.{Allowed, Verbosity}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.commons.ResponseContext.FinalState
import tech.beshu.ror.commons.shims.request.RequestContextShim

import scala.collection.JavaConverters._

class DeprecatedAuditLogSerializerAdapter[T](underlying: tech.beshu.ror.requestcontext.AuditLogSerializer[T])
  extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    val deprecatedResponseContext = responseContext match {
      case Allowed(_, verbosity, _) if verbosity == Verbosity.Info => None
      case _ => Some(toDeprecatedResponseContext(responseContext))
    }
    deprecatedResponseContext
      .map(underlying.createLoggableEntry)
      .map(entry => new JSONObject(entry))
  }

  private def toDeprecatedResponseContext(responseContext: AuditResponseContext) = {
    responseContext match {
      case AuditResponseContext.Allowed(requestContext, verbosity, reason) =>
        new tech.beshu.ror.commons.ResponseContext(
          FinalState.ALLOWED,
          toDeprecatedRequestContext(requestContext),
          null,
          toDeprecatedVerbosity(verbosity),
          reason,
          true
        )
      case AuditResponseContext.ForbiddenBy(requestContext, verbosity, reason) =>
        new tech.beshu.ror.commons.ResponseContext(
          FinalState.FORBIDDEN,
          toDeprecatedRequestContext(requestContext),
          null,
          toDeprecatedVerbosity(verbosity),
          reason,
          true
        )
      case AuditResponseContext.Forbidden(requestContext) =>
        new tech.beshu.ror.commons.ResponseContext(
          FinalState.FORBIDDEN,
          toDeprecatedRequestContext(requestContext),
          null,
          null,
          "default",
          false
        )
      case AuditResponseContext.Errored(requestContext, cause) =>
        new tech.beshu.ror.commons.ResponseContext(
          FinalState.ERRORED,
          toDeprecatedRequestContext(requestContext),
          cause,
          null,
          "error",
          false
        )
      case AuditResponseContext.NotFound(requestContext, cause) =>
        new tech.beshu.ror.commons.ResponseContext(
          FinalState.NOT_FOUND,
          toDeprecatedRequestContext(requestContext),
          cause,
          null,
          "not found",
          false
        )
    }
  }

  private def toDeprecatedRequestContext(requestContext: AuditRequestContext) = {
    new RequestContextShim {
      override val getId: String = requestContext.id
      override val getIndices: util.Set[String] = requestContext.indices.asJava
      override val getTimestamp: Date = Date.from(requestContext.timestamp)
      override val getAction: String = requestContext.action
      override val getHeaders: util.Map[String, String] = requestContext.headers.asJava
      override val getUri: String = requestContext.uriPath
      override val getHistoryString: String = requestContext.history
      override val getContent: String = requestContext.content
      override val getContentLength: Integer = requestContext.contentLength
      override val getRemoteAddress: String = requestContext.remoteAddress
      override val getLocalAddress: String = requestContext.localAddress
      override val getType: String = requestContext.`type`
      override val getTaskId: lang.Long = requestContext.taskId
      override val getMethodString: String = requestContext.httpMethod
      override val getLoggedInUserName: Optional[String] = Optional.ofNullable(requestContext.loggedInUserName.orNull)
      override val involvesIndices: Boolean = requestContext.involvesIndices
    }
  }

  private def toDeprecatedVerbosity(verbosity: Verbosity) = verbosity match {
    case Verbosity.Info => tech.beshu.ror.commons.Verbosity.INFO
    case Verbosity.Error => tech.beshu.ror.commons.Verbosity.ERROR
  }
}
