package tech.beshu.ror.utils

import monix.eval.Task
import monix.execution.CancelablePromise
import org.elasticsearch.action.ActionListener

class ActionListenerToTaskAdapter[RESPONSE] extends ActionListener[RESPONSE] {
  private val promise = CancelablePromise[RESPONSE]()

  def result: Task[RESPONSE] = Task.fromCancelablePromise(promise)

  override def onResponse(result: RESPONSE): Unit = {
    promise.success(result)
  }

  def onFailure(ex: Exception): Unit = {
    promise.failure(ex)
  }
}