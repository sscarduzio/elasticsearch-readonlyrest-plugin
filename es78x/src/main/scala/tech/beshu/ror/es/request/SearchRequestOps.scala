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
package tech.beshu.ror.es.request

import cats.data.NonEmptySet
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.query.{QueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain.{DocumentField, Filter}

object SearchRequestOps extends Logging {

  implicit class FilterOps(val request: SearchRequest) extends AnyVal {

    def applyFilterToQuery(filter: Option[Filter]): SearchRequest = {
      filter match {
        case Some(definedFilter) =>
          val filterQuery = QueryBuilders.wrapperQuery(definedFilter.value.value)
          val modifiedQuery = provideNewQueryWithAppliedFilter(request, filterQuery)
          request.source().query(modifiedQuery)
        case None =>
          logger.debug(s"No filter applied to query.")
      }
      request
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
  }

  implicit class FieldsOps(val request: SearchRequest) extends AnyVal {

    def applyFilterToFields(fields: Option[NonEmptySet[DocumentField]]): SearchRequest = {
      import SourceFiltering._
      val originalFetchSource = request.source().fetchSource()

      val sourceFilteringResult = originalFetchSource.applyNewFields(fields)

      request.source().fetchSource(sourceFilteringResult.modifiedContext)
      request
    }
  }
}
