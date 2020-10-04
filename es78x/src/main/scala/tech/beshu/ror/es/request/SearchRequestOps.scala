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
import cats.syntax.show._
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.query.{QueryBuilder, QueryBuilders}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, RequestFieldsUsage}
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Filter, Header}
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.request.queries.QueryFieldsUsage.instances._
import tech.beshu.ror.es.request.queries.QueryFieldsUsage.{Ops => QueryFieldsUsageOps}
import tech.beshu.ror.es.request.queries.QueryWithModifiableFields.instances._
import tech.beshu.ror.es.request.queries.QueryWithModifiableFields.{Ops => QueryWithModifiableFieldsOps}

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

    def applyFieldLevelSecurity(fieldLevelSecurity: Option[FieldLevelSecurity],
                                threadPool: ThreadPool,
                                requestId: RequestContext.Id): SearchRequest = {
      fieldLevelSecurity match {
        case Some(definedFields) =>
          definedFields.strategy match {
            case FlsAtLuceneLevelApproach =>
              addContextHeader(threadPool, definedFields.restrictions, requestId)
              disableCaching(requestId)
            case BasedOnBlockContextOnly.NotAllowedFieldsUsed(notAllowedFields) =>
              modifyNotAllowedFieldsInQuery(notAllowedFields)
            case BasedOnBlockContextOnly.EverythingAllowed=>
              request
          }
        case None =>
          request
      }
    }

    def checkFieldsUsage(): RequestFieldsUsage = {
      Option(request.source().scriptFields()) match {
        case Some(scriptFields) if scriptFields.size() > 0 =>
          RequestFieldsUsage.CannotExtractFields
        case _ =>
          checkQueryFields()
      }
    }

    private def modifyNotAllowedFieldsInQuery(notAllowedFields: NonEmptyList[UsedField.SpecificField]) = {
      val currentQuery = request.source().query()
      val newQuery = currentQuery.handleNotAllowedFields(notAllowedFields)
      request.source().query(newQuery)
      request
    }


    private def checkQueryFields(): RequestFieldsUsage = {
      Option(request.source().query())
        .map(_.fieldsUsage)
        .getOrElse(RequestFieldsUsage.NotUsingFields)
    }

    private def addContextHeader(threadPool: ThreadPool,
                                 fieldsRestrictions: FieldsRestrictions,
                                 requestId: RequestContext.Id): Unit = {
      val threadContext = threadPool.getThreadContext
      val header = createContextHeader(fieldsRestrictions)
      logger.debug(s"[${requestId.show}] Adding thread context header required by lucene. Header Value: '${header.value.value}'")
      threadContext.putHeader(header.name.value.value, header.value.value)
    }

    private def createContextHeader(fieldsRestrictions: FieldsRestrictions) = {
      new Header(
        Name.transientFields,
        transientFieldsToHeaderValue.toRawValue(fieldsRestrictions)
      )
    }

    private def disableCaching(requestId: RequestContext.Id) = {
      logger.debug(s"[${requestId.show}] ACL uses context header for fields rule, will disable request cache for SearchRequest")
      request.requestCache(false)
    }
  }
}