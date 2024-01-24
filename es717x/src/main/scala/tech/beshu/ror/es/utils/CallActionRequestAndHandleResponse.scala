package tech.beshu.ror.es.utils

import monix.eval.Task
import monix.execution.atomic.Atomic
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionRequestBuilder, ActionResponse}
import org.elasticsearch.client.Cancellable

import scala.concurrent.Promise
import scala.language.implicitConversions

class CallActionRequestAndHandleResponse[REQUEST <: ActionRequest, RESPONSE <: ActionResponse] private(builder: ActionRequestBuilder[REQUEST, RESPONSE]) {

  def call[R](f: RESPONSE => R): Task[R] = {
    val listener = new GenericResponseListener[RESPONSE]()
    builder.execute(listener)
    listener.result(f)
  }
}

object CallActionRequestAndHandleResponse {
  implicit def toOps[REQUEST <: ActionRequest, RESPONSE <: ActionResponse](builder: ActionRequestBuilder[REQUEST, RESPONSE]): CallActionRequestAndHandleResponse[REQUEST, RESPONSE] =
    new CallActionRequestAndHandleResponse(builder)
}

class InvokeCallerAndHandleResponse[RESPONSE <: ActionResponse] private(caller: ActionListener[RESPONSE] => _) {

  def execute[R](f: RESPONSE => R): Task[R] = {
    val listener = new GenericResponseListener[RESPONSE]()
    caller(listener)
    listener.result(f)
  }
}

object InvokeCallerAndHandleResponse {
  implicit def toOps[RESPONSE <: ActionResponse](caller: ActionListener[RESPONSE] => Cancellable): InvokeCallerAndHandleResponse[RESPONSE] =
    new InvokeCallerAndHandleResponse(caller)
}

private final class GenericResponseListener[RESPONSE <: ActionResponse] extends ActionListener[RESPONSE] {

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