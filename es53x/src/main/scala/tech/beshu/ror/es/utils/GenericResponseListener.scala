package tech.beshu.ror.es.utils

import monix.eval.Task
import org.elasticsearch.action.{ActionListener, ActionResponse}

import scala.concurrent.Promise

final class GenericResponseListener[RESPONSE <: ActionResponse] extends ActionListener[RESPONSE] {

  private val promise = Promise[RESPONSE]

  def result: Task[RESPONSE] = Task.fromFuture(promise.future)

  override def onResponse(response: RESPONSE): Unit = {
    promise.success(response)
  }

  override def onFailure(exception: Exception): Unit = {
    promise.failure(exception)
  }
}
