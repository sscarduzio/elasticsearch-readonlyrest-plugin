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
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.elasticsearch.SearchManager.{AsyncSearchResult, FieldCapsResult, MSearchResult, SearchResult}
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import ujson.Value

import scala.util.Try

class SearchManager(client: RestClient,
                    override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  def search(indexName: String, query: JSON): SearchResult =
    call(createSearchRequest(Some(indexName), query), new SearchResult(_))

  def search(query: JSON): SearchResult =
    call(createSearchRequest(None, query), new SearchResult(_))

  def search(indexNames: String*): SearchResult =
    call(createSearchRequest(indexNames.toList), new SearchResult(_))

  def searchAll(indexName: String): SearchResult = {
    val queryAll = ujson.read(
      s"""{
         |  "query": {
         |    "match_all": {}
         |  }
         |}""".stripMargin
    )
    search(indexName, queryAll)
  }

  def asyncSearch(indexName: String, indexNames: String*): AsyncSearchResult = {
    call(createAsyncSearchRequest(indexName :: indexNames.toList, None), new AsyncSearchResult(_))
  }

  def asyncSearch(indexName: String, body: JSON): AsyncSearchResult = {
    call(createAsyncSearchRequest(indexName :: Nil, Some(body)), new AsyncSearchResult(_))
  }

  def mSearchUnsafe(lines: String*): MSearchResult = {
    lines.toList match {
      case Nil => throw new IllegalArgumentException("At least one line should be passed to mSearch query")
      case head :: rest => mSearch(head, rest: _*)
    }
  }

  def mSearch(line: String, lines: String*): MSearchResult = {
    val payload = (lines.toSeq :+ "\n").foldLeft(line) { case (acc, elem) => s"$acc\n$elem" }
    call(createMultiSearchRequest(payload), new MSearchResult(_))
  }

  def searchTemplate(index: String, query: JSON): SearchResult = {
    call(createSearchTemplateRequest(index, query), new SearchResult(_))
  }

  def mSearchTemplate(line: JSON, lines: JSON*): MSearchResult = {
    val payload = (lines.map(ujson.write(_)) :+ "\n").foldLeft(ujson.write(line)) { case (acc, elem) => s"$acc\n$elem" }
    call(createMultiSearchTemplateRequest(payload), new MSearchResult(_))
  }

  def fieldCaps(indices: List[String], fields: List[String]): FieldCapsResult = {
    call(createFieldCapsRequest(indices.mkString(","), fields.mkString(",")), new FieldCapsResult(_))
  }

  def renderTemplate(query: String): JsonResponse =
    call(createRenderTemplateRequest(query), new JsonResponse(_))

  private def createSearchRequest(indexName: Option[String], query: JSON) = {
    val request = new HttpPost(client.from(
      indexName match {
        case Some(name) => s"/$name/_search"
        case None => "/_search"
      },
      Map("size" -> "100")
    ))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(query)))
    request
  }

  private def createSearchRequest(indexNames: List[String] = Nil) = {
    new HttpPost(client.from(
      indexNames match {
        case Nil => "/_search"
        case names => s"/${names.mkString(",")}/_search"
      },
      Map("size" -> "100")
    ))
  }

  private def createAsyncSearchRequest(indexNames: List[String],
                                       body: Option[JSON]) = {
    val request = new HttpPost(client.from(
      indexNames match {
        case Nil => "/_async_search"
        case names => s"/${names.mkString(",")}/_async_search"
      },
      Map("size" -> "10")
    ))
    body match {
      case Some(b) =>
        request.addHeader("Content-Type", "application/json")
        request.setEntity(new StringEntity(ujson.write(b)))
      case None =>
    }
    request
  }

  private def createMultiSearchRequest(payload: String) = {
    val request = new HttpPost(client.from("/_msearch"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(payload))
    request
  }

  private def createRenderTemplateRequest(query : String) = {
    val request = new HttpGetWithEntity(client.from("_render/template"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }

  private def createSearchTemplateRequest(index: String, query: JSON) = {
    val request = new HttpGetWithEntity(client.from(s"/$index/_search/template"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(query)))
    request
  }

  private def createMultiSearchTemplateRequest(payload: String) = {
    val request = new HttpGetWithEntity(client.from(s"/_msearch/template"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(payload))
    request
  }

  private def createFieldCapsRequest(indicesStr: String, fieldsStr: String) = {
    new HttpGet(client.from(s"/$indicesStr/_field_caps", Map("fields" -> fieldsStr)))
  }
}

object SearchManager {

  implicit class SearchResultOps(val hits: Traversable[Value]) extends AnyVal {
    def removeRorSettings(): Vector[Value] = hits.filter(hit => hit("_index").str != ".readonlyrest").toVector
  }

  abstract class BaseSearchResult(response: HttpResponse) extends JsonResponse(response) {
    protected def searchHitsWithSettings: Value

    lazy val searchHits: List[Value] = searchHitsWithSettings.arr.removeRorSettings().toList
    lazy val docIds: List[String] = searchHits.map(_ ("_id").str)

    def hit(idx: Int) = searchHits(idx)("_source")

    def head = hit(0)

    def id(docId: String) = searchHits.find(_ ("_id").str == docId).get("_source")
  }

  class SearchResult(response: HttpResponse) extends BaseSearchResult(response) {
    override lazy val searchHitsWithSettings: Value = force().responseJson("hits")("hits")
    lazy val aggregations: Map[String, Value] = responseJson("aggregations").obj.toMap

    lazy val totalHits: Int = force().responseJson("hits")("total")("value").num.toInt
  }

  class AsyncSearchResult(response: HttpResponse) extends BaseSearchResult(response) {
    override lazy val searchHitsWithSettings: Value = force().responseJson("response")("hits")("hits")
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