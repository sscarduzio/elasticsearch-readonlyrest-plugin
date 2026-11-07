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
import org.elasticsearch.action.ActionListener
import org.elasticsearch.client.{Response, ResponseListener}
import tech.beshu.ror.utils.TaskOps.fromEsFuture

import scala.concurrent.Promise

object EsCalls {

  // Calls ES when the task runs. The task gives the result of the call.
  def esCall[T](call: ActionListener[T] => Unit): Task[T] = Task.defer {
    val listener = new ActionListenerToTaskAdapter[T]
    call(listener)
    listener.result
  }

  // Calls the ES REST client when the task runs. The task gives the response.
  def esRestCall(call: ResponseListener => Unit): Task[Response] = Task.defer {
    val listener = new ResponseListenerToTaskAdapter
    call(listener)
    listener.result
  }

  private class ActionListenerToTaskAdapter[T] extends ActionListener[T] {
    private val promise = Promise[T]()

    def result: Task[T] = Task.fromEsFuture(promise.future)

    override def onResponse(response: T): Unit = {
      promise.trySuccess(response)
    }

    override def onFailure(exception: Exception): Unit = {
      promise.tryFailure(exception)
    }

  }

  private class ResponseListenerToTaskAdapter extends ResponseListener {
    private val promise = Promise[Response]()

    def result: Task[Response] = Task.fromEsFuture(promise.future)

    override def onSuccess(response: Response): Unit = {
      promise.trySuccess(response)
    }

    override def onFailure(exception: Exception): Unit = {
      promise.tryFailure(exception)
    }

  }

}
