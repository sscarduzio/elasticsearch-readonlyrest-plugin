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
import tech.beshu.ror.utils.elasticsearch.AuditIndexManager.AuditEntriesResult
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.SearchManager.SearchResult
import tech.beshu.ror.utils.httpclient.RestClient

class AuditIndexManager(restClient: RestClient,
                        esVersion: String,
                        indexName: String)
  extends BaseManager(restClient) {

  private lazy val searchManager = new SearchManager(restClient, esVersion)
  private lazy val indexManager = new IndexManager(restClient, esVersion)

  def getEntries: AuditEntriesResult =
    eventually(new AuditEntriesResult(searchManager.search(indexName)))(
      until = _.jsons.nonEmpty
    )

  def truncate: SimpleResponse =
    indexManager.removeIndex(indexName)

}

object AuditIndexManager {

  class AuditEntriesResult(response: HttpResponse) extends SearchResult(response) {

    def this(searchResult: SearchResult) = {
      this(AuditEntriesResult.refreshEntity(searchResult))
    }

    lazy val jsons: Vector[JSON] =
      searchHits
        .map(hit => hit("_source"))
        .toVector

  }
  object AuditEntriesResult {
    private def refreshEntity(simpleResponse: SimpleResponse) = {
      simpleResponse.response.setEntity(new StringEntity(simpleResponse.body))
      simpleResponse.response
    }
  }
}