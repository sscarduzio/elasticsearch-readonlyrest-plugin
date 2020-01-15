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
package tech.beshu.ror.accesscontrol.logging

import java.time.{Clock, Instant}
import java.time.format.DateTimeFormatter

import cats.Show
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.{Address, Header}
import tech.beshu.ror.accesscontrol.blocks.Block.{History, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.logging.AuditingTool.Settings
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.AuditSink

class AuditingTool(settings: Settings,
                   auditSink: AuditSink)
                  (implicit clock: Clock,
                   loggingContext: LoggingContext) {

  def audit(response: ResponseContext): Task[Unit] = {
    safeRunSerializer(response)
      .map {
        case Some(entry) =>
          auditSink.submit(
            settings.indexNameFormatter.format(Instant.now(clock)),
            response.requestContext.id.value,
            entry.toString
          )
        case None =>
      }
  }

  private def toAuditResponse(responseContext: ResponseContext) = {
    responseContext match {
      case ResponseContext.AllowedBy(requestContext, block, blockContext, history) =>
        AuditResponseContext.Allowed(
          toAuditRequestContext(requestContext, Some(blockContext), history),
          toAuditVerbosity(block.verbosity),
          block.show
        )
      case ResponseContext.Allow(requestContext, _, block, history) =>
        AuditResponseContext.Allowed(
          toAuditRequestContext(requestContext, None, history),
          toAuditVerbosity(Block.Verbosity.Info),
          block.show
        )
      case ResponseContext.ForbiddenBy(requestContext, block, blockContext, history) =>
        AuditResponseContext.ForbiddenBy(
          toAuditRequestContext(requestContext, Some(blockContext), history),
          toAuditVerbosity(block.verbosity),
          block.show
        )
      case ResponseContext.Forbidden(requestContext, history) =>
        AuditResponseContext.Forbidden(toAuditRequestContext(requestContext, None, history))
      case ResponseContext.RequestedIndexNotExist(requestContext, history) =>
        AuditResponseContext.RequestedIndexNotExist(toAuditRequestContext(requestContext, None, history))
      case ResponseContext.Errored(requestContext, cause) =>
        AuditResponseContext.Errored(toAuditRequestContext(requestContext, None, Vector.empty), cause)
    }
  }

  private def toAuditRequestContext(requestContext: RequestContext,
                                    blockContext: Option[BlockContext],
                                    historyEntries: Vector[History]): AuditRequestContext = {
    new AuditRequestContext {
      implicit val showHeader:Show[Header] = obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)
      override val timestamp: Instant = requestContext.timestamp
      override val id: String = requestContext.id.value
      override val indices: Set[String] = requestContext.indices.map(_.value.value)
      override val action: String = requestContext.action.value
      override val headers: Map[String, String] = requestContext.headers.map(h => (h.name.value.value, h.value.value)).toMap
      override val uriPath: String = requestContext.uriPath.value
      override val history: String = historyEntries.map(_.show).mkString(", ")
      override val content: String = requestContext.content
      override val contentLength: Integer = requestContext.contentLength.toBytes.toInt
      override val remoteAddress: String = requestContext.remoteAddress match {
        case Some(Address.Ip(value)) => value.toString
        case Some(Address.Name(value)) => value.toString
        case None => "N/A"
      }
      override val localAddress: String = requestContext.localAddress  match {
        case Address.Ip(value) => value.toString
        case Address.Name(value) => value.toString
      }
      override val `type`: String = requestContext.`type`.value
      override val taskId: Long = requestContext.taskId
      override val httpMethod: String = requestContext.method.m
      override val loggedInUserName: Option[String] = blockContext.flatMap(_.loggedUser.map(_.id.value.value))
      override val impersonatedByUserName: Option[String] = blockContext.flatMap(_.loggedUser).flatMap {
        case DirectlyLoggedUser(_) => None
        case ImpersonatedUser(_, impersonatedBy) => Some(impersonatedBy.value.value)
      }
      override val involvesIndices: Boolean = requestContext.involvesIndices
      override val attemptedUserName: Option[String] = requestContext.basicAuth.map(_.credentials.user.value.value)
      override val rawAuthHeader: Option[String] = requestContext.rawAuthHeader.map(_.value.value)
    }
  }

  private def toAuditVerbosity(verbosity: Verbosity) = verbosity match {
    case Verbosity.Info => AuditResponseContext.Verbosity.Info
    case Verbosity.Error => AuditResponseContext.Verbosity.Error
  }

  private def safeRunSerializer(response: ResponseContext) = {
    Task(settings.logSerializer.onResponse(toAuditResponse(response)))
  }
}

object AuditingTool {

  final case class Settings(indexNameFormatter: DateTimeFormatter,
                            logSerializer: AuditLogSerializer)

}
