package tech.beshu.ror.es.request

import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.index.get.GetResult
import org.elasticsearch.index.query.QueryBuilders
import tech.beshu.ror.accesscontrol.domain.{Filter, IndexName}

object DocumentApiOps {

  final case class DocumentId(value: String) extends AnyVal

  final case class DocumentWithIndex(index: IndexName, documentId: DocumentId)

  sealed trait DocumentAccessibility
  object DocumentAccessibility {
    case object Accessible extends DocumentAccessibility
    case object Inaccessible extends DocumentAccessibility
  }

  def createSearchRequest(nodeClient: NodeClient,
                          filter: Filter)
                         (documentWithIndex: DocumentWithIndex): SearchRequestBuilder = {
    val wrappedQueryFromFilter = QueryBuilders.wrapperQuery(filter.value.value)
    val composedQuery = QueryBuilders
      .boolQuery()
      .filter(QueryBuilders.constantScoreQuery(wrappedQueryFromFilter))
      .filter(QueryBuilders.idsQuery().addIds(documentWithIndex.documentId.value))

    nodeClient
      .prepareSearch(documentWithIndex.index.value.value)
      .setQuery(composedQuery)
  }

  object GetApi {

    //it's ugly but I don't know better way to do it
    def doesNotExistResponse(original: GetResponse) = {
      val exists = false
      val source = null
      val result = new GetResult(
        original.getIndex,
        original.getType,
        original.getId,
        original.getSeqNo,
        original.getPrimaryTerm,
        original.getVersion,
        exists,
        source,
        java.util.Collections.emptyMap(),
        java.util.Collections.emptyMap())
      new GetResponse(result)
    }

    implicit class GetResponseOps(val response: GetResponse) extends AnyVal {
      def asDocumentWithIndex = createDocumentWithIndex(response.getIndex, response.getId)
    }

  }

  object MultiGetApi {

    implicit class MultiGetItemResponseOps(val item: MultiGetItemResponse) extends AnyVal {
      def asDocumentWithIndex = createDocumentWithIndex(item.getIndex, item.getId)
    }

  }

  private def createDocumentWithIndex(indexStr: String, docId: String) = {
    val indexName = createIndexName(indexStr)
    val documentId = DocumentId(docId)
    DocumentWithIndex(indexName, documentId)
  }

  private def createIndexName(indexStr: String) = {
    IndexName
      .fromString(indexStr)
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
  }

}
