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
package tech.beshu.ror.acl.logging

import java.time.{Clock, Instant}
import java.time.format.DateTimeFormatter

import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.acl.domain.Address
import tech.beshu.ror.acl.blocks.Block.{History, Verbosity}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.logging.AuditingTool.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}

class AuditingTool(settings: Settings,
                   auditSink: AuditSink)
                  (implicit clock: Clock) {

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
      case ResponseContext.Allowed(requestContext, block, blockContext, history) =>
        AuditResponseContext.Allowed(
          toAuditRequestContext(requestContext, Some(blockContext), history),
          toAuditVerbosity(block.verbosity),
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
      case ResponseContext.Errored(requestContext, cause) =>
        AuditResponseContext.Errored(toAuditRequestContext(requestContext, None, Vector.empty), cause)
      case ResponseContext.NotFound(requestContext, cause) =>
        AuditResponseContext.NotFound(toAuditRequestContext(requestContext, None, Vector.empty), cause)
    }
  }

  private def toAuditRequestContext(requestContext: RequestContext,
                                    blockContext: Option[BlockContext],
                                    historyEntries: Vector[History]): AuditRequestContext = {
    new AuditRequestContext {
      override val timestamp: Instant = requestContext.timestamp
      override val id: String = requestContext.id.value
      override val indices: Set[String] = requestContext.indices.map(_.value)
      override val action: String = requestContext.action.value
      override val headers: Map[String, String] = requestContext.headers.map(h => (h.name.value.value, h.value.value)).toMap
      override val uriPath: String = requestContext.uriPath.value
      override val history: String = historyEntries.map(_.show).mkString(", ")
      override val content: String = requestContext.content
      override val contentLength: Integer = requestContext.contentLength.toBytes.toInt
      override val remoteAddress: String = requestContext.remoteAddress match {
        case Address.Ip(value) => value.toString
        case Address.Name(value) => value.toString
      }
      override val localAddress: String = requestContext.localAddress  match {
        case Address.Ip(value) => value.toString
        case Address.Name(value) => value.toString
      }
      override val `type`: String = requestContext.`type`.value
      override val taskId: Long = requestContext.taskId
      override val httpMethod: String = requestContext.method.m
      override val loggedInUserName: Option[String] = blockContext.flatMap(_.loggedUser.map(_.id.value))
      override val involvesIndices: Boolean = requestContext.involvesIndices
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

trait AuditSink {
  def submit(indexName: String, documentId: String, jsonRecord: String): Unit
}
