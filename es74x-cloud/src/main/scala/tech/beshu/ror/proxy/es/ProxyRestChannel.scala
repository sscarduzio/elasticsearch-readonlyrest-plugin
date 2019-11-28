package tech.beshu.ror.proxy.es

import monix.eval.Task
import org.elasticsearch.common.io.stream.BytesStreamOutput
import org.elasticsearch.common.xcontent.{XContentBuilder, XContentFactory, XContentType}
import org.elasticsearch.rest.{RestChannel, RestRequest, RestResponse}

import scala.concurrent.Promise

class ProxyRestChannel(restRequest: RestRequest) extends RestChannel {

  // todo: think if we are always be able to complete it (maybe timeout is needed here?)
  private val resultPromise = Promise[EsRestServiceSimulator.ProcessingResult]()

  def result: Task[EsRestServiceSimulator.ProcessingResult] = Task.fromFuture(resultPromise.future)

  override def newBuilder(): XContentBuilder =
    XContentBuilder.builder(XContentFactory.xContent(XContentType.JSON))

  override def newErrorBuilder(): XContentBuilder = newBuilder()

  override def newBuilder(xContentType: XContentType,
                          useFiltering: Boolean): XContentBuilder =
    XContentBuilder.builder(XContentFactory.xContent(xContentType))

  override def newBuilder(xContentType: XContentType,
                          responseContentType: XContentType,
                          useFiltering: Boolean): XContentBuilder =
    XContentBuilder.builder(XContentFactory.xContent(xContentType))

  override def bytesOutput(): BytesStreamOutput = new BytesStreamOutput()

  override def request(): RestRequest = restRequest

  override def detailedErrorsEnabled(): Boolean = true

  override def sendResponse(response: RestResponse): Unit = {
    resultPromise.trySuccess(EsRestServiceSimulator.ProcessingResult.Response(response))
  }

  def passThrough(): Unit = {
    resultPromise.trySuccess(EsRestServiceSimulator.ProcessingResult.PassThrough)
  }
}
