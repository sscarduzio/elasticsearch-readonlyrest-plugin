package tech.beshu.ror.es.request.context

import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.utils.ScalaOps._

trait EsRequest[B <: BlockContext[B]] {
  def threadPool: ThreadPool

  final def modifyUsing(blockContext: B): Unit = {
    threadPool.getThreadContext.stashContext.bracket { _ =>
      modifyCommonParts(blockContext)
      modifyRequest(blockContext)
    }
  }

  protected def modifyRequest(blockContext: B): Unit

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
    blockContext.responseHeaders.foreach(header =>
      threadContext.putHeader(header.name.value.value, header.value.value))
  }
}


















