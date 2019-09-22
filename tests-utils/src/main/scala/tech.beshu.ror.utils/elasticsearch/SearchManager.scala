package tech.beshu.ror.utils.elasticsearch

import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.RestClient
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import tech.beshu.ror.utils.elasticsearch.SearchManager.SearchResult
import ujson.Value

import scala.util.Try

class SearchManager(client: RestClient)
  extends BaseManager(client) {

  def search(endpoint: String): SearchResult = {
    call(createSearchRequest(endpoint), new SearchResult(_))
  }

  private def createSearchRequest(endpoint: String) = {
    val request = new HttpGet(client.from(endpoint))
    request.setHeader("timeout", "50s")
    request
  }
}

object SearchManager {
  class SearchResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val searchHits: Try[Value] = Try(responseJson("hits")("hits"))
  }
}