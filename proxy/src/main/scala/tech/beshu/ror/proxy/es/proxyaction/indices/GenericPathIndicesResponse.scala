//package tech.beshu.ror.proxy.es.proxyaction.indices
//
//import org.elasticsearch.action.ActionResponse
//import org.elasticsearch.client.Response
//import org.elasticsearch.common.io.stream.StreamOutput
//import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
//import org.elasticsearch.common.xcontent.json.JsonXContent
//import org.elasticsearch.rest.{BytesRestResponse, RestResponse, RestStatus}
//
//import scala.io.Source
//
//class GenericPathIndicesResponse(val response: Response)
//  extends ActionResponse with ToXContentObject {
//
//  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder =
//    JsonXContent.contentBuilder()
//
//  override def writeTo(out: StreamOutput): Unit = ()
//
//  def toRestResponse: RestResponse = {
//    val status = RestStatus.fromCode(response.getStatusLine.getStatusCode)
//    val content = Source.fromInputStream(response.getEntity.getContent).mkString
//    response.getEntity.getContentType.getElements.toList match {
//      case Nil =>
//        new BytesRestResponse(status, content)
//      case contentTypeHeader :: _ =>
//        new BytesRestResponse(status, contentTypeHeader.getName, content)
//    }
//  }
//}
