/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import monix.eval.Task
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.tasks.{Task => EsTask}
import org.elasticsearch.rest._
import org.elasticsearch.tasks.TaskManager

import scala.concurrent.Promise

sealed abstract class ProxyRestChannel(val restRequest: RestRequest)
  extends AbstractRestChannel(restRequest, true) {

  def taskManager: TaskManager

  def result: Task[EsRestServiceSimulator.ProcessingResult]
  def sendFailureResponse(exception: Throwable): Unit
  def passThrough(): Unit
}

class PromiseBasedProxyRestChannel(restRequest: RestRequest, override val taskManager: TaskManager)
  extends ProxyRestChannel(restRequest) {

  // todo: think if we are always be able to complete it (maybe timeout is needed here?)
  private val resultPromise = Promise[EsRestServiceSimulator.ProcessingResult]()

  def result: Task[EsRestServiceSimulator.ProcessingResult] = Task.fromFuture(resultPromise.future)

  override def sendResponse(response: RestResponse): Unit = {
    resultPromise.trySuccess(EsRestServiceSimulator.ProcessingResult.Response(response))
  }

  override def sendFailureResponse(exception: Throwable): Unit = {
    sendResponse(failureResponseFrom(exception))
  }

  override def passThrough(): Unit = {
    resultPromise.trySuccess(EsRestServiceSimulator.ProcessingResult.PassThrough)
  }

  private def failureResponseFrom(exception: Throwable): BytesRestResponse = {
    exception match {
      case ex: ElasticsearchException => new BytesRestResponse(this, ex)
      case ex: Exception => new PromiseBasedProxyRestChannel.FailureResponse(this, ex)
      case throwable => new PromiseBasedProxyRestChannel.FailureResponse(this, new Exception(throwable))
    }
  }
}

class UnregisteringTaskProxyRestChannel(underlying: ProxyRestChannel, task: EsTask)
  extends ProxyRestChannel(underlying.restRequest) {

  override def result: Task[EsRestServiceSimulator.ProcessingResult] = underlying.result
  override def taskManager: TaskManager = underlying.taskManager

  override def sendResponse(response: RestResponse): Unit = {
    taskManager.unregister(task)
    underlying.sendResponse(response)
  }

  override def sendFailureResponse(exception: Throwable): Unit = {
    taskManager.unregister(task)
    underlying.sendFailureResponse(exception)
  }

  override def passThrough(): Unit = {
    taskManager.unregister(task)
    underlying.passThrough()
  }
}

private object PromiseBasedProxyRestChannel {
  private class FailureResponse(restChannel: RestChannel, exception: Exception)
    extends BytesRestResponse(restChannel, RestStatus.INTERNAL_SERVER_ERROR, exception)
}