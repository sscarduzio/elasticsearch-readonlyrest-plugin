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
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers.shouldBe
import tech.beshu.ror.utils.JsonReader.ujsonRead
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.Value

class AuditIndexManager(restClient: RestClient,
                        esVersion: String,
                        indexName: String)
  extends BaseManager(restClient, esVersion, esNativeApi = true) {

  final lazy val searchManager = new SearchManager(restClient, esVersion)
  final lazy val indexManager = new IndexManager(restClient, esVersion)
  final lazy val documentManager = new DocumentManager(restClient, esVersion)

  def getEntries: AuditEntriesResult = {
    val result = searchManager.eventually(searchManager.search(indexName))(
      until = r => new AuditEntriesResult(r).jsons.nonEmpty
    )
    new AuditEntriesResult(result)
  }

  def truncate(): Unit = {
    val matchAllQuery = ujsonRead("""{"query" : {"match_all" : {}}}""".stripMargin)
    documentManager.deleteByQuery(indexName, matchAllQuery)
    indexManager.refresh(indexName)
  }

  def hasNoEntries: Assertion = {
    indexManager.refresh(indexName).force()
    val result = new AuditEntriesResult(searchManager.search(indexName))
    result.jsons.toList shouldBe List.empty[Value]
  }

  class AuditEntriesResult(response: HttpResponse)
    extends searchManager.SearchResult(response) {

    def this(searchResult: searchManager.SearchResult) = {
      this(AuditEntriesResult.refreshEntity(searchResult))
    }

    lazy val jsons: Vector[Value] =
      searchHits
        .map(hit => hit("_source"))
        .toVector

  }
  object AuditEntriesResult {
    private def refreshEntity(simpleResponse: searchManager.SimpleResponse) = {
      simpleResponse.response.setEntity(new StringEntity(simpleResponse.body))
      simpleResponse.response
    }
  }
}