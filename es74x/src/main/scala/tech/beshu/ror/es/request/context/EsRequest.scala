package tech.beshu.ror.es.request.context

import cats.implicits._
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext

import scala.util.Try

trait EsRequest[B <: BlockContext] extends Logging {
  def threadPool: ThreadPool

  final def modifyUsing(blockContext: B): ModificationResult = {
    modifyCommonParts(blockContext)
    Try(modifyRequest(blockContext))
      .fold(
        ex => {
          logger.error(s"[${blockContext.requestContext.id.show}] Cannot modify request with filtered data", ex)
          ModificationResult.CannotModify
        },
        identity
      )
  }

  protected def modifyRequest(blockContext: B): ModificationResult

  private def modifyCommonParts(blockContext: B): Unit = {
    modifyResponseHeaders(blockContext)
    modifyThreadContextHeaders(blockContext)
  }

  private def modifyResponseHeaders(blockContext: B): Unit = {
    val threadContext = threadPool.getThreadContext
    blockContext.responseHeaders.foreach(header =>
      threadContext.addResponseHeader(header.name.value.value, header.value.value))
  }

  private def modifyThreadContextHeaders(blockContext: B): Unit = {
    val threadContext = threadPool.getThreadContext
    blockContext.contextHeaders.foreach(header =>
      threadContext.putHeader(header.name.value.value, header.value.value))
  }
}

sealed trait ModificationResult
object ModificationResult {
  case object Modified extends ModificationResult
  case object CannotModify extends ModificationResult
  case object ShouldBeInterrupted extends ModificationResult
}

















