/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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