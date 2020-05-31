/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import monix.eval.Task
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.rest._

import scala.concurrent.Promise

class ProxyRestChannel(restRequest: RestRequest)
  extends AbstractRestChannel(restRequest, true) {

  // todo: think if we are always be able to complete it (maybe timeout is needed here?)
  private val resultPromise = Promise[EsRestServiceSimulator.ProcessingResult]()

  def result: Task[EsRestServiceSimulator.ProcessingResult] = Task.fromFuture(resultPromise.future)

  def sendFailureResponse(exception: Throwable): Unit = {
    sendResponse(failureResponseFrom(exception))
  }

  def failureResponseFrom(exception: Throwable): BytesRestResponse = {
    exception match {
      case ex: ElasticsearchException => new BytesRestResponse(this, ex)
      case ex: Exception => new ProxyRestChannel.FailureResponse(this, ex)
      case throwable => new ProxyRestChannel.FailureResponse(this, new Exception(throwable))
    }
  }

  def passThrough(): Unit = {
    resultPromise.trySuccess(EsRestServiceSimulator.ProcessingResult.PassThrough)
  }

  override def sendResponse(response: RestResponse): Unit = {
    resultPromise.trySuccess(EsRestServiceSimulator.ProcessingResult.Response(response))
  }
}

private object ProxyRestChannel {
  private class FailureResponse(restChannel: RestChannel, exception: Exception)
    extends BytesRestResponse(restChannel, RestStatus.INTERNAL_SERVER_ERROR, exception)
}