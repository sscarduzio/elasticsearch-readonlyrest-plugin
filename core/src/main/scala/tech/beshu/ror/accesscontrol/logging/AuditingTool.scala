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
import cats.implicits._
import monix.eval.Task
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.blocks.Block.{History, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditIndexTemplate}
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
            settings.rorAuditIndexTemplate.indexName(Instant.now(clock)).name.value,
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
          requestContext = toAuditRequestContext(
            requestContext = allowedBy.requestContext,
            blockContext = Some(allowedBy.blockContext),
            userMetadata = Some(allowedBy.blockContext.userMetadata),
            historyEntries = allowedBy.history,
            generalAuditEvents = allowedBy.requestContext.generalAuditEvents
          ),
          verbosity = toAuditVerbosity(allowedBy.block.verbosity),
          reason = allowedBy.block.show
        )
      case allow: ResponseContext.Allow[B] =>
        AuditResponseContext.Allowed(
          requestContext = toAuditRequestContext(
            requestContext = allow.requestContext,
            blockContext = None,
            userMetadata = Some(allow.userMetadata),
            historyEntries = allow.history,
            generalAuditEvents = allow.requestContext.generalAuditEvents
          ),
          verbosity = toAuditVerbosity(Block.Verbosity.Info),
          reason = allow.block.show
        )
      case forbiddenBy: ResponseContext.ForbiddenBy[B] =>
        AuditResponseContext.ForbiddenBy(
          requestContext = toAuditRequestContext(
            requestContext = forbiddenBy.requestContext,
            blockContext = Some(forbiddenBy.blockContext),
            userMetadata = Some(forbiddenBy.blockContext.userMetadata),
            historyEntries = forbiddenBy.history),
          verbosity = toAuditVerbosity(forbiddenBy.block.verbosity),
          reason = forbiddenBy.block.show
        )
      case forbidden: ResponseContext.Forbidden[B] =>
        AuditResponseContext.Forbidden(toAuditRequestContext(
          requestContext = forbidden.requestContext,
          blockContext = None,
          userMetadata = None,
          historyEntries = forbidden.history))
      case requestedIndexNotExist: ResponseContext.RequestedIndexNotExist[B] =>
        AuditResponseContext.RequestedIndexNotExist(
          toAuditRequestContext(
            requestContext = requestedIndexNotExist.requestContext,
            blockContext = None,
            userMetadata = None,
            historyEntries = requestedIndexNotExist.history)
        )
      case errored: ResponseContext.Errored[B] =>
        AuditResponseContext.Errored(
          requestContext = toAuditRequestContext(
            requestContext = errored.requestContext,
            blockContext = None,
            userMetadata = None,
            historyEntries = Vector.empty),
          cause = errored.cause)
    }
  }

  private def toAuditVerbosity(verbosity: Verbosity) = verbosity match {
    case Verbosity.Info => AuditResponseContext.Verbosity.Info
    case Verbosity.Error => AuditResponseContext.Verbosity.Error
  }

  private def toAuditRequestContext[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                       blockContext: Option[B],
                                                       userMetadata: Option[UserMetadata],
                                                       historyEntries: Vector[History[B]],
                                                       generalAuditEvents: JSONObject = new JSONObject()): AuditRequestContext = {
    new AuditRequestContextBasedOnAclResult(
      requestContext,
      userMetadata,
      historyEntries,
      loggingContext,
      generalAuditEvents,
      hasIndices(blockContext)
    )
  }

  private def hasIndices[B <: BlockContext](blockContext: Option[B]) =
    blockContext.exists(_.containsIndices)

}

object AuditingTool {

  final case class Settings(rorAuditIndexTemplate: RorAuditIndexTemplate,
                            logSerializer: AuditLogSerializer,
                            auditCluster: Option[AuditCluster])

}
