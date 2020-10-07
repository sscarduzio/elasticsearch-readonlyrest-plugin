package tech.beshu.ror.es.utils

import org.elasticsearch.common.util.concurrent.ThreadContext
import tech.beshu.ror.utils.JavaConverters

import scala.language.implicitConversions

final class ThreadContextOps(threadContext: ThreadContext) {
  def stashAndMergeResponseHeaders(): ThreadContext.StoredContext = {
    val responseHeaders = JavaConverters.flattenPair(threadContext.getResponseHeaders)
    val storedContext = threadContext.stashContext()
    responseHeaders.foreach { case (k, v) => threadContext.addResponseHeader(k, v) }
    storedContext
  }
}

object ThreadContextOps {
  implicit def createThreadContextOps(threadContext: ThreadContext): ThreadContextOps = new ThreadContextOps(threadContext)
}
