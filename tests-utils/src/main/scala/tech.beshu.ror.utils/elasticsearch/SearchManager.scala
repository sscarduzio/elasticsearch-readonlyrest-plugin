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

import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.elasticsearch.SearchManager.{MSearchResult, SearchResult}
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import ujson.Value

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

class SearchManager(client: RestClient,
                    override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  def search(endpoint: String, query: String): SearchResult =
    call(createSearchRequest(endpoint, query), new SearchResult(_))

  def search(endpoint: String): SearchResult =
    call(createSearchRequest(endpoint), new SearchResult(_))

  def msearch(query: String): MSearchResult =
    call(createMSearchRequest(query), new MSearchResult(_))

  def renderTemplate(query: String): JsonResponse =
    call(createRenderTemplateRequest(query), new JsonResponse(_))

  private def createMSearchRequest(query: String) = {
    val request = new HttpPost(client.from("/_msearch"))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }

  private def createSearchRequest(endpoint: String, query: String) = {
    val request = new HttpPost(client.from(endpoint))
    request.setHeader("timeout", "50s")
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }

  private def createSearchRequest(endpoint: String) = {
    val request = new HttpGet(client.from(endpoint))
    request.setHeader("timeout", "50s")
    request
  }

  private def createRenderTemplateRequest(query: String) = {
    val request = new HttpGetWithEntity(client.from("_render/template"))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }
}

object SearchManager {

  implicit class SearchResultOps (val hits: ArrayBuffer[Value]) extends AnyVal {
    def removeRorSettings() = hits.filter(hit => hit("_index").str != ".readonlyrest")
  }

  class SearchResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val searchHits = responseJson("hits")("hits")
    lazy val searchHitsNoSettings = searchHits.arr.removeRorSettings()
  }

  class MSearchResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val responses = responseJson("responses").arr

    def searchHitsForResponse(responseIdx: Int) = responses(responseIdx)("hits")("hits").arr

    def searchHitsNoSettingsForResponse(responseIdx: Int) = searchHitsForResponse(responseIdx).removeRorSettings()

    def totalHitsForResponse(responseIdx: Int): Int =
      Try(responses(responseIdx)("hits")("total")("value").num.toInt)
        .getOrElse(responses(responseIdx)("hits")("total").num.toInt)
  }
}