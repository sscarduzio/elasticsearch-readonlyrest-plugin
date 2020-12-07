package tech.beshu.ror.proxy.es.proxyaction

import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.Response
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import org.elasticsearch.rest.{BytesRestResponse, RestRequest, RestResponse, RestStatus}
import tech.beshu.ror.proxy.es.ProxyRestChannel

import scala.io.Source

trait ByProxyProcessedRequest extends ActionRequest {

  def actionName: String
  def rest: RestRequest
}

class ByProxyProcessedResponse(response: Response)
  extends ActionResponse with ToXContentObject {

  def toRestResponse: RestResponse = {
    val status = RestStatus.fromCode(response.getStatusLine.getStatusCode)
    val content = Source.fromInputStream(response.getEntity.getContent).mkString
    response.getEntity.getContentType.getElements.toList match {
      case Nil =>
        new BytesRestResponse(status, content)
      case contentTypeHeader :: _ =>
        new BytesRestResponse(status, contentTypeHeader.getName, content)
    }
  }

  override def writeTo(out: StreamOutput): Unit = ()

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder =
    JsonXContent.contentBuilder()
}

class ByProxyProcessedResponseActionListener(restChannel: ProxyRestChannel)
  extends ActionListener[ByProxyProcessedResponse] {

  override def onResponse(response: ByProxyProcessedResponse): Unit = {
    restChannel.sendResponse(response.toRestResponse)
  }

  override def onFailure(e: Exception): Unit = {
    restChannel.sendFailureResponse(e)
  }
}