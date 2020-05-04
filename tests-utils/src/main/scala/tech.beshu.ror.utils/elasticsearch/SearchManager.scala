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
import tech.beshu.ror.utils.elasticsearch.SearchManager.{FieldCapsResult, MSearchResult, SearchResult}
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import ujson.Value

import scala.collection.JavaConverters._
import scala.util.Try

class SearchManager(client: RestClient,
                    override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  def search(endpoint: String, query: String): SearchResult =
    call(createSearchRequest(endpoint, query), new SearchResult(_))

  def search(endpoint: String): SearchResult =
    call(createSearchRequest(endpoint), new SearchResult(_))

  def mSearchUnsafe(lines: String*): MSearchResult = {
    lines.toList match {
      case Nil => throw new IllegalArgumentException("At least one line should be passed to mSearch query")
      case head :: rest => mSearch(head, rest: _*)
    }
  }

  def mSearch(line: String, lines: String*): MSearchResult = {
    val payload = (lines.toSeq :+ "\n").foldLeft(line) { case (acc, elem) => s"$acc\n$elem" }
    call(createMSearchRequest(payload), new MSearchResult(_))
  }

  def fieldCaps(indices: List[String], fields: List[String]): FieldCapsResult = {
    call(createFieldCapsRequest(indices.mkString(","), fields.mkString(",")), new FieldCapsResult(_))
  }

  def renderTemplate(query: String): JsonResponse =
    call(createRenderTemplateRequest(query), new JsonResponse(_))

  private def createSearchRequest(endpoint: String, query: String) = {
    val request = new HttpPost(client.from(endpoint))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }

  private def createSearchRequest(endpoint: String) = {
    new HttpGet(client.from(endpoint))
  }

  private def createMSearchRequest(payload: String) = {
    val request = new HttpPost(client.from("/_msearch"))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(payload))
    request
  }

  private def createRenderTemplateRequest(query: String) = {
    val request = new HttpGetWithEntity(client.from("_render/template"))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }

  private def createFieldCapsRequest(indicesStr: String, fieldsStr: String) = {
    new HttpGet(client.from(s"/$indicesStr/_field_caps", Map("fields" -> fieldsStr).asJava))
  }
}

object SearchManager {

  implicit class SearchResultOps(val hits: Traversable[Value]) extends AnyVal {
    def removeRorSettings(): Vector[Value] = hits.filter(hit => hit("_index").str != ".readonlyrest").toVector
  }

  class SearchResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val searchHitsWithSettings: Value = responseJson("hits")("hits")
    lazy val searchHits: List[Value] = searchHitsWithSettings.arr.removeRorSettings().toList
  }

  class MSearchResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val responses: Vector[Value] = responseJson("responses").arr.toVector

    def searchHitsForResponseWithSettings(responseIdx: Int): Vector[Value] =
      responses(responseIdx)("hits")("hits").arr.toVector

    def searchHitsForResponse(responseIdx: Int): Vector[Value] =
      searchHitsForResponseWithSettings(responseIdx).removeRorSettings()

    def totalHitsForResponse(responseIdx: Int): Int =
      Try(responses(responseIdx)("hits")("total")("value").num.toInt)
        .getOrElse(responses(responseIdx)("hits")("total").num.toInt)
  }

  class FieldCapsResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val indices: Vector[String] = responseJson("indices").arr.map(_.str).toVector
    lazy val fields: Map[String, Value] = responseJson("fields").obj.toMap
  }
}