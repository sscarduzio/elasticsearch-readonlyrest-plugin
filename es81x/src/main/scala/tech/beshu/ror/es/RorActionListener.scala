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
package tech.beshu.ror.es

import cats.Eval
import cats.implicits.*
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.CorrelationId
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.utils.ThreadContextOps.createThreadContextOps

sealed abstract class RorActionListener[T](val underlying: ActionListener[T]) extends ActionListener[T] {

  override def onResponse(response: T): Unit = underlying.onResponse(response)

  override def onFailure(e: Exception): Unit = underlying.onFailure(e)
}

final class HidingInternalErrorDetailsRorActionListener[T](underlying: ActionListener[T],
                                                           correlationId: Eval[CorrelationId])
  extends RorActionListener[T](underlying) with Logging {

  override def onFailure(e: Exception): Unit = {
    super.onFailure(adaptExceptionIfNeeded(e))
  }

  private def adaptExceptionIfNeeded(ex: Exception) = {
    ex match {
      case esException: ElasticsearchException => esException
      case other =>
        logger.error(s"[${correlationId.value.show}] Internal error.", other)
        new ElasticsearchException(s"[${correlationId.value.show}] Internal error. See logs for details.")
    }
  }
}

final class AtEsLevelUpdateActionResponseListener(esContext: EsContext,
                                                  update: ActionResponse => Task[ActionResponse],
                                                  threadPool: ThreadPool)
                                                 (implicit scheduler: Scheduler)
  extends RorActionListener[ActionResponse](esContext.listener.underlying) {

  override def onResponse(response: ActionResponse): Unit = {
    val stashedContext = threadPool.getThreadContext.stashAndMergeResponseHeaders(esContext)
    update(response) runAsync {
      case Right(updatedResponse) =>
        stashedContext.restore()
        super.onResponse(updatedResponse)
      case Left(ex) =>
        stashedContext.close()
        onFailure(new Exception(ex))
    }
  }
}