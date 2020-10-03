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

import cats.data.NonEmptyList
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.query.{QueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.BasedOnBlockContextOnly.{NotAllowedFieldsUsed, EverythingAllowed}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.FlsAtLuceneLevelApproach
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Filter}
import tech.beshu.ror.es.request.queries.QueryWithModifiableFields._
import tech.beshu.ror.es.request.queries.QueryWithModifiableFields.instances._

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

    def applyFieldLevelSecurity(fieldLevelSecurity: Option[FieldLevelSecurity]): SearchRequest = {
      fieldLevelSecurity match {
        case Some(definedFields) =>
          definedFields.strategy match {
            case NotAllowedFieldsUsed(notAllowedFields) =>
              modifyNotAllowedFieldsInQuery(notAllowedFields)
            case EverythingAllowed | FlsAtLuceneLevelApproach =>

              request
          }
        case None =>
          request
      }
    }

    private def modifyNotAllowedFieldsInQuery(notAllowedFields: NonEmptyList[UsedField.SpecificField]) = {
      val currentQuery = request.source().query()
      val newQuery = currentQuery.handleNotAllowedFields(notAllowedFields)
      request.source().query(newQuery)
      request
    }
  }
}
