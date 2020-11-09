package tech.beshu.ror.utils.elasticsearch

import org.apache.http.HttpResponse
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.AuditIndexManager.AuditEntriesResult
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.SearchManager.SearchResult
import tech.beshu.ror.utils.httpclient.RestClient

class AuditIndexManager(restClient: RestClient, indexName: String)
  extends BaseManager(restClient) {

  private lazy val searchManager = new SearchManager(restClient)
  private lazy val indexManager = new IndexManager(restClient)

  def getEntries: AuditEntriesResult =
    eventually(new AuditEntriesResult(searchManager.search(indexName)))(
      until = _.jsons.nonEmpty
    )

  def truncate: SimpleResponse =
    indexManager.removeIndex(indexName)

}

object AuditIndexManager {

  class AuditEntriesResult(response: HttpResponse) extends SearchResult(response) {

    def this(searchResult: SearchResult) {
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