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
import org.elasticsearch.action.{ActionListener, ActionResponse}
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

  // Calls ES when the task runs. The task gives the result of `f` on the response.
  // ES releases the response when the listener returns, so the listener keeps it until `f` ends.
  def esRefCountedCall[RESPONSE <: ActionResponse, R](call: ActionListener[RESPONSE] => Unit)(
      f: RESPONSE => R
  ): Task[R] = Task.defer {
    val listener = new RefCountedResponseListener[RESPONSE]
    call(listener)
    listener.result(f)
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

  private class RefCountedResponseListener[RESPONSE <: ActionResponse] extends ActionListener[RESPONSE] {
    import ResponseState.*

    private val promise = Promise[RESPONSE]()
    private val state = Atomic(Waiting: ResponseState)

    def result[R](f: RESPONSE => R): Task[R] =
      Task.fromEsFuture(promise.future).map(f).guarantee(Task.delay(release()))

    override def onResponse(response: RESPONSE): Unit = {
      response.incRef()
      if (state.compareAndSet(Waiting, Holding(response))) promise.trySuccess(response)
      else response.decRef()
    }

    override def onFailure(exception: Exception): Unit = {
      promise.tryFailure(exception)
    }

    // Releases the held response. After this, onResponse releases a late response at once.
    private def release(): Unit = state.getAndSet(Released) match {
      case Holding(response)  => response.decRef()
      case Waiting | Released => ()
    }

  }

  private sealed trait ResponseState

  private object ResponseState {
    case object Waiting extends ResponseState
    final case class Holding(response: ActionResponse) extends ResponseState
    case object Released extends ResponseState
  }

}
