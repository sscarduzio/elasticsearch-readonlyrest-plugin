package tech.beshu.ror.utils.elasticsearch

import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.elasticsearch.ScriptManager.StoreResult
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.Value

import scala.util.Try

class ScriptManager(client: RestClient)
  extends BaseManager(client) {
  def store(endpoint: String, query: String): StoreResult =
    call(createStoreRequest(endpoint, query), new StoreResult(_))

  private def createStoreRequest(endpoint: String, query: String) = {
    val request = new HttpPost(client.from(endpoint))
    request.setHeader("timeout", "50s")
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }
}

object ScriptManager {
  class StoreResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val searchHits: Try[Value] = Try(responseJson("hits")("hits"))
  }
}