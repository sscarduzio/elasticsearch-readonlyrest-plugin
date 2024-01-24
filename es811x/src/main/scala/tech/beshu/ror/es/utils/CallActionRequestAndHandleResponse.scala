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
package tech.beshu.ror.es.utils

import monix.eval.Task
import monix.execution.atomic.Atomic
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionRequestBuilder, ActionResponse}

import scala.concurrent.Promise
import scala.language.implicitConversions

class CallActionRequestAndHandleResponse[REQUEST <: ActionRequest, RESPONSE <: ActionResponse] private(builder: ActionRequestBuilder[REQUEST, RESPONSE]) {

  def call[R](f: RESPONSE => R): Task[R] = {
    val listener = new GenericResponseListener()
    builder.execute(listener)
    listener.result(f)
  }

  private final class GenericResponseListener extends ActionListener[RESPONSE] {

    private val promise = Promise[RESPONSE]()
    private val finalizer = Atomic(Task.unit)

    def result[T](f: RESPONSE => T): Task[T] = Task
      .fromFuture(promise.future)
      .map(f)
      .guarantee(finalizer.getAndSet(Task.unit))

    override def onResponse(response: RESPONSE): Unit = {
      response.incRef()
      finalizer.set(Task.delay(response.decRef()))
      promise.success(response)
    }

    override def onFailure(exception: Exception): Unit = {
      promise.failure(exception)
    }
  }
}

object CallActionRequestAndHandleResponse {
  implicit def toOps[REQUEST <: ActionRequest, RESPONSE <: ActionResponse](builder: ActionRequestBuilder[REQUEST, RESPONSE]): CallActionRequestAndHandleResponse[REQUEST, RESPONSE] =
    new CallActionRequestAndHandleResponse(builder)
}