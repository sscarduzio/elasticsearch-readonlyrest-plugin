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

import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant}

import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Block.{History, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.logging.AuditingTool.Settings
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.es.AuditSinkService

class AuditingTool(settings: Settings,
                   auditSink: AuditSinkService)
                  (implicit clock: Clock,
                   loggingContext: LoggingContext) {

  def audit[B <: BlockContext](response: ResponseContext[B]): Task[Unit] = {
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

  private def safeRunSerializer[B <: BlockContext](response: ResponseContext[B]) = {
    Task(settings.logSerializer.onResponse(toAuditResponse(response)))
  }

  private def toAuditResponse[B <: BlockContext](responseContext: ResponseContext[B]) = {
    responseContext match {
      case allowedBy: ResponseContext.AllowedBy[B] =>
        AuditResponseContext.Allowed(
          requestContext = toAuditRequestContext(allowedBy.requestContext, Some(allowedBy.blockContext), allowedBy.history),
          verbosity = toAuditVerbosity(allowedBy.block.verbosity),
          reason = allowedBy.block.show
        )
      case allow: ResponseContext.Allow[B] =>
        AuditResponseContext.Allowed(
          requestContext = toAuditRequestContext(allow.requestContext, allow.history.collectFirst { case History(allow.block.name, _, blockContext) => blockContext }, allow.history),
          verbosity = toAuditVerbosity(Block.Verbosity.Info),
          reason = allow.block.show
        )
      case forbiddenBy: ResponseContext.ForbiddenBy[B] =>
        AuditResponseContext.ForbiddenBy(
          requestContext = toAuditRequestContext(forbiddenBy.requestContext, Some(forbiddenBy.blockContext), forbiddenBy.history),
          verbosity = toAuditVerbosity(forbiddenBy.block.verbosity),
          reason = forbiddenBy.block.show
        )
      case forbidden: ResponseContext.Forbidden[B] =>
        AuditResponseContext.Forbidden(toAuditRequestContext(forbidden.requestContext, None, forbidden.history))
      case requestedIndexNotExist: ResponseContext.RequestedIndexNotExist[B] =>
        AuditResponseContext.RequestedIndexNotExist(
          toAuditRequestContext(requestedIndexNotExist.requestContext, None, requestedIndexNotExist.history)
        )
      case errored: ResponseContext.Errored[B] =>
        AuditResponseContext.Errored(toAuditRequestContext(errored.requestContext, None, Vector.empty), errored.cause)
    }
  }

  private def toAuditVerbosity(verbosity: Verbosity) = verbosity match {
    case Verbosity.Info => AuditResponseContext.Verbosity.Info
    case Verbosity.Error => AuditResponseContext.Verbosity.Error
  }

  private def toAuditRequestContext[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                       blockContext: Option[B],
                                                       historyEntries: Vector[History[B]]): AuditRequestContext = {
    new AuditRequestContextBasedOnAclResult(requestContext, blockContext, historyEntries, loggingContext)
  }

}

object AuditingTool {

  final case class Settings(indexNameFormatter: DateTimeFormatter,
                            logSerializer: AuditLogSerializer)

}
