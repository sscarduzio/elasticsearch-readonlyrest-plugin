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
package tech.beshu.ror.audit.adapters

import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext.{Allowed, Verbosity}
import tech.beshu.ror.audit.instances.SerializeUser
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.commons.ResponseContext.FinalState
import tech.beshu.ror.commons.shims.request.RequestContextShim

import java.util.{Date, Optional}
import java.{lang, util}
import scala.collection.JavaConverters._

class DeprecatedAuditLogSerializerAdapter[T](underlying: tech.beshu.ror.requestcontext.AuditLogSerializer[T])
  extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    val deprecatedResponseContext = responseContext match {
      case Allowed(_, verbosity, _) =>
        verbosity match {
          case Verbosity.Info =>
            Some(toDeprecatedResponseContext(responseContext))
          case Verbosity.Error =>
            None
        }
      case _ =>
        Some(toDeprecatedResponseContext(responseContext))
    }
    deprecatedResponseContext
      .map(underlying.createLoggableEntry)
      .map {
        case entry: java.util.Map[_, _] =>
          new JSONObject(entry)
        case entry =>
          JsonObjectJavaAdapter.create(entry)
      }
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
      case AuditResponseContext.RequestedIndexNotExist(requestContext) =>
        new tech.beshu.ror.commons.ResponseContext(
          FinalState.NOT_FOUND,
          toDeprecatedRequestContext(requestContext),
          null,
          null,
          "index not found",
          false
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
    }
  }

  private def toDeprecatedRequestContext(requestContext: AuditRequestContext) = {
    new RequestContextShim {
      override val getId: String = requestContext.id
      override val getCorrelationId: String = requestContext.correlationId
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
      override val getLoggedInUserName: Optional[String] = Optional.ofNullable(SerializeUser.serialize(requestContext).orNull)
      override val involvesIndices: Boolean = requestContext.involvesIndices
    }
  }

  private def toDeprecatedVerbosity(verbosity: Verbosity) = verbosity match {
    case Verbosity.Info => tech.beshu.ror.commons.Verbosity.INFO
    case Verbosity.Error => tech.beshu.ror.commons.Verbosity.ERROR
  }
}
