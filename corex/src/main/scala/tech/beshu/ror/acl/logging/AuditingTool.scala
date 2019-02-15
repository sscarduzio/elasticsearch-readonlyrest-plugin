package tech.beshu.ror.acl.logging

import java.time.Instant
import java.time.format.DateTimeFormatter

import cats.implicits._
import io.circe.Encoder
import tech.beshu.ror.acl.blocks.Block.{History, Verbosity}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.logging.AuditingTool.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}

class AuditingTool(settings: Settings,
                   auditSink: AuditSink) {

  def audit(response: ResponseContext): Unit =  {
    settings.logSerializer.onResponse(toAuditResponse(response)) match {
      case Some(entry) =>
        auditSink.submit(
          settings.indexNameFormatter.format(Instant.now()),
          response.requestContext.id.value,
          toJson(entry).noSpaces
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
                                    history: Vector[History]): AuditRequestContext = {
    new AuditRequestContext {
      override val timestamp: Instant = requestContext.timestamp
      override val id: String = requestContext.id.value
      override val indices: Set[String] = requestContext.indices.map(_.value)
      override val action: String = requestContext.action.value
      override val headers: Map[String, String] = requestContext.headers.map(h => (h.name.value.value, h.value.value)).toMap
      override val uriPath: String = requestContext.uriPath.value
      override val history: String = history.map(_.show).mkString(", ")
      override val content: String = requestContext.content
      override val contentLength: Integer = requestContext.contentLength.toBytes.toInt
      override val remoteAddress: String = requestContext.remoteAddress.value
      override val localAddress: String = requestContext.localAddress.value
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

  private def toJson(entry: Map[String, String]) = {
    Encoder[Map[String, String]].apply(entry)
  }
}

object AuditingTool {
  final case class Settings(indexNameFormatter: DateTimeFormatter,
                            logSerializer: AuditLogSerializer)
}

trait AuditSink {
  def submit(indexName: String, documentId: String, jsonRecord: String): Unit
}
