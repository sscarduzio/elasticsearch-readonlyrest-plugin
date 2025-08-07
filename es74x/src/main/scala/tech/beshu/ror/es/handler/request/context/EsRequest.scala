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
package tech.beshu.ror.es.handler.request.context

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.util.Try

trait EsRequest[B <: BlockContext] extends Logging {
  implicit def threadPool: ThreadPool

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

  def modifyWhenIndexNotFound: ModificationResult = ModificationResult.CannotModify

  def modifyWhenAliasNotFound: ModificationResult = ModificationResult.CannotModify

  def modifyWhenTemplateNotFound: ModificationResult = ModificationResult.CannotModify

  protected def modifyRequest(blockContext: B): ModificationResult

  private def modifyCommonParts(blockContext: B): Unit = {
    modifyResponseHeaders(blockContext)
  }

  private def modifyResponseHeaders(blockContext: B): Unit = {
    val threadContext = threadPool.getThreadContext
    blockContext.responseHeaders.foreach(header =>
      threadContext.addResponseHeader(header.name.value.value, header.value.value))
  }
}

sealed trait ModificationResult
object ModificationResult {
  case object Modified extends ModificationResult
  case object CannotModify extends ModificationResult
  case object ShouldBeInterrupted extends ModificationResult
  final case class CustomResponse(response: ActionResponse) extends ModificationResult
  final case class UpdateResponse private(update: ActionResponse => Task[ActionResponse]) extends ModificationResult

  object UpdateResponse {
    def using(update: ActionResponse => ActionResponse): UpdateResponse = {
      new UpdateResponse(response => Task.now(doPrivileged(update(response))))
    }
    def create(update: ActionResponse => Task[ActionResponse]): UpdateResponse = {
      new UpdateResponse(response => Task.defer(doPrivileged(update(response))))
    }
  }
}