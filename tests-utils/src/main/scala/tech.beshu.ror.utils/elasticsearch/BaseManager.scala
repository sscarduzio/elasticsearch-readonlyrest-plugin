package tech.beshu.ror.utils.elasticsearch

import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpUriRequest
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleResponse
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.HttpResponseHelper.stringBodyFrom
import tech.beshu.ror.utils.misc.ScalaUtils._
import ujson.Value

abstract class BaseManager(client: RestClient) {

  protected def call[T <: SimpleResponse](request: HttpUriRequest, fromResponse: HttpResponse => T): T = {
    client
      .execute {
        additionalHeaders.foldLeft(request) {
          case (req, (name, value)) =>
            req.addHeader(name, value)
            req
        }
      }
      .bracket(fromResponse)
  }

  protected def additionalHeaders: Map[String, String] = Map.empty

}

object BaseManager {

  class SimpleResponse private[elasticsearch](response: HttpResponse) {
    val getResponseCode: Int = response.getStatusLine.getStatusCode
    val isSuccess: Boolean = getResponseCode / 100 == 2
  }

  class JsonResponse(response: HttpResponse) extends SimpleResponse(response) {
    val body: String = stringBodyFrom(response)
    val responseJson: Value = ujson.read(body)
  }
}