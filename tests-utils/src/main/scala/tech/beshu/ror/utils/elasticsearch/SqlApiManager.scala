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
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import tech.beshu.ror.utils.misc.Version
import ujson.Arr

class SqlApiManager(restClient: RestClient, esVersion: String) extends BaseManager(restClient, esVersion, true) {

  def execute(selectQuery: String): SqlResult = {
    call(createSqlQueryRequest(selectQuery), new SqlResult(_))
  }

  def execute(selectQuery: String, fetchSize: Int): SqlResult = {
    call(createSqlQueryRequest(selectQuery, Some(fetchSize)), new SqlResult(_))
  }

  def nextPage(cursor: String): SqlResult = {
    call(createSqlRequest(s"""{ "cursor": "$cursor" }"""), new SqlResult(_))
  }

  private def createSqlQueryRequest(query: String, fetchSize: Option[Int] = None) = {
    createSqlRequest(requestBody(query, fetchSize))
  }

  private def requestBody(query: String, fetchSize: Option[Int]) = fetchSize match {
    case Some(definedFetchSize) => s"""{ "query": "$query", "fetch_size": $definedFetchSize }"""
    case None                   => s"""{ "query": "$query" }"""
  }

  private def createSqlRequest(body: String) = {
    val request = if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      new HttpGetWithEntity(restClient.from("_sql", Map("format" -> "json")))
    } else {
      new HttpGetWithEntity(restClient.from("_xpack/sql", Map("format" -> "json")))
    }
    request.setHeader("Content-Type", "application/json")
    request.setHeader("timeout", "50s")
    request.setEntity(new StringEntity(body))
    request
  }

  class SqlResult(response: HttpResponse) extends JsonResponse(response) {

    lazy val queryResult: Map[String, Vector[JSON]] = {
      val columns = responseJson("columns").arr.map(_.obj("name").str).toVector
      val rows = responseJson("rows").arr.toVector.map(_.arr.toVector).transpose
      columns.zip(rows).toMap
    }

    lazy val columnNames: List[String] = responseJson("columns").arr.toList.map(_.obj("name").str)
    lazy val rows: Vector[JSON] = responseJson("rows").arr.toVector
    lazy val columns: Vector[JSON] = responseJson("columns").arr.toVector

    def row(idx: Int): Arr = rows(idx).arr

    def rowValue(rowIdx: Int, valueIdx: Int): JSON = row(rowIdx).arr.toVector(valueIdx)

    def rowValue(rowIdx: Int, columnName: String): JSON = row(rowIdx).arr.toVector(columnIdxBy(columnName))

    def column(columnName: String): Vector[JSON] = rows.map(_.arr.toVector(columnIdxBy(columnName)))

    private def columnIdxBy(name: String) = columnNames.indexOf(name)
  }

}
