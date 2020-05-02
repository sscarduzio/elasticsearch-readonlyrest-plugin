package tech.beshu.ror.es.dlsfls

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.query.{QueryBuilder, QueryBuilders}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.utils.FilterUtils

object SearchQueryDecorator extends Logging {

  def applyFilterToQuery(request: SearchRequest, threadPool: ThreadPool) = {
    Option(threadPool.getThreadContext.getHeader(Constants.FILTER_TRANSIENT)) match {
      case Some(definedFilter) =>
        val filterQuery = createFilterQuery(definedFilter)
        val modifiedQuery = provideNewQueryWithAppliedFilter(request, filterQuery)
        request.source().query(modifiedQuery)
      case None =>
        logger.debug(s"Header: '${Constants.FIELDS_TRANSIENT}' not found in threadContext. No filter applied to query.")
    }
  }

  private def provideNewQueryWithAppliedFilter(request: SearchRequest, filterQuery: QueryBuilder) = {
    Option(request.source().query()) match {
      case Some(requestedQuery) =>
        QueryBuilders.boolQuery()
          .must(requestedQuery)
          .filter(filterQuery)
      case None =>
        QueryBuilders.constantScoreQuery(filterQuery)
    }
  }

  private def createFilterQuery(definedFilter: String) = {
    val filterQuery = FilterUtils.filterFromHeaderValue(definedFilter)
    QueryBuilders.wrapperQuery(filterQuery)
  }
}