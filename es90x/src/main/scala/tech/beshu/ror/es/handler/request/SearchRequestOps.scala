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
package tech.beshu.ror.es.handler.request

import cats.data.NonEmptyList
import cats.implicits.*
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.query.{AbstractQueryBuilder, QueryBuilder, QueryBuilders}
import org.elasticsearch.search.aggregations.AggregatorFactories
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregationBuilder
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{CannotExtractFields, NotUsingFields, UsedField, UsingFields}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Filter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.handler.request.queries.QueryFieldsUsage.Ops as QueryFieldsUsageOps
import tech.beshu.ror.es.handler.request.queries.QueryFieldsUsage.instances.*
import tech.beshu.ror.es.handler.request.queries.QueryWithModifiableFields.Ops as QueryWithModifiableFieldsOps
import tech.beshu.ror.es.handler.request.queries.QueryWithModifiableFields.instances.*
import tech.beshu.ror.es.handler.response.FLSContextHeaderHandler
import tech.beshu.ror.implicits.*

import java.util.UUID
import scala.jdk.CollectionConverters.*

object SearchRequestOps extends Logging {

  implicit class QueryBuilderOps(val builder: Option[QueryBuilder]) extends AnyVal {

    def wrapQueryBuilder(filter: Option[Filter])
                        (implicit requestId: RequestContext.Id): Option[QueryBuilder] = {
      filter match {
        case Some(definedFilter) =>
          val filterQuery = QueryBuilders.wrapperQuery(definedFilter.value.value)
          val modifiedQuery: AbstractQueryBuilder[_] = provideNewQueryWithAppliedFilter(builder, filterQuery)
          Some(modifiedQuery)
        case None =>
          logger.debug(s"[${requestId.show}] No filter applied to query.")
          builder
      }
    }

    private def provideNewQueryWithAppliedFilter(queryBuilder: Option[QueryBuilder],
                                                 filterQuery: QueryBuilder): AbstractQueryBuilder[_] = {
      queryBuilder match {
        case Some(requestedQuery) =>
          QueryBuilders.boolQuery()
            .must(requestedQuery)
            .filter(filterQuery)
        case None =>
          QueryBuilders.constantScoreQuery(filterQuery)
      }
    }
  }

  implicit class FilterOps(val request: SearchRequest) extends AnyVal {

    def applyFilterToQuery(filter: Option[Filter])
                          (implicit requestId: RequestContext.Id): SearchRequest = {
      Option(request.source().query())
        .wrapQueryBuilder(filter)
        .foreach { newQueryBuilder =>
          request.source().query(newQueryBuilder)
        }
      request
    }
  }

  implicit class FieldsOps(val request: SearchRequest) extends AnyVal {

    def applyFieldLevelSecurity(fieldLevelSecurity: Option[FieldLevelSecurity])
                               (implicit threadPool: ThreadPool,
                                requestId: RequestContext.Id): SearchRequest = {
      fieldLevelSecurity match {
        case Some(definedFields) =>
          definedFields.strategy match {
            case FlsAtLuceneLevelApproach =>
              FLSContextHeaderHandler.addContextHeader(threadPool, definedFields.restrictions, requestId)
              disableCaching(requestId)
            case BasedOnBlockContextOnly.NotAllowedFieldsUsed(notAllowedFields) =>
              modifyNotAllowedFieldsInRequest(notAllowedFields)
            case BasedOnBlockContextOnly.EverythingAllowed =>
              request
          }
        case None =>
          request
      }
    }

    def checkFieldsUsage(): RequestFieldsUsage = {
      checkFieldsUsageBaseOnScrollExistence()
        .getOrElse(checkFieldsUsageBaseOnRequestSource())
    }

    private def checkFieldsUsageBaseOnScrollExistence() = {
      // we have to use Lucene when scroll is used
      Option(request.scroll()).map(_ => CannotExtractFields)
    }

    private def checkFieldsUsageBaseOnRequestSource() = {
      Option(request.source()) match {
        case Some(source) if source.hasScriptFields =>
          CannotExtractFields
        case Some(source) =>
          source.fieldsUsageInAggregations |+| source.fieldsUsageInQuery
        case None =>
          NotUsingFields
      }
    }

    private def modifyNotAllowedFieldsInRequest(notAllowedFields: NonEmptyList[UsedField.SpecificField]) = {
      Option(request.source()) match {
        case None =>
          request
        case Some(sourceBuilder) =>
          request.source(
            sourceBuilder
              .modifyNotAllowedFieldsInQuery(notAllowedFields)
              .modifyNotAllowedFieldsInAggregations(notAllowedFields)
          )
      }
    }

    private def disableCaching(requestId: RequestContext.Id) = {
      logger.debug(s"[${requestId.show}] ACL uses context header for fields rule, will disable request cache for SearchRequest")
      request.requestCache(false)
    }
  }

  private implicit class SearchSourceBuilderOps(val builder: SearchSourceBuilder) extends AnyVal {

    def modifyNotAllowedFieldsInQuery(notAllowedFields: NonEmptyList[UsedField.SpecificField]): SearchSourceBuilder = {
      Option(builder.query()) match {
        case None =>
          builder
        case Some(currentQuery) =>
          val newQuery = currentQuery.handleNotAllowedFields(notAllowedFields)
          builder.query(newQuery)
      }
    }

    def modifyNotAllowedFieldsInAggregations(notAllowedFields: NonEmptyList[UsedField.SpecificField]): SearchSourceBuilder = {
      def modifyBuilder(aggregatorFactoryBuilder: AggregatorFactories.Builder) = {
        import org.joor.Reflect.*
        on(builder).set("aggregations", aggregatorFactoryBuilder)
        builder
      }

      Option(builder.aggregations()) match {
        case None =>
          builder
        case Some(aggregations) =>
          val aggregatorFactoryBuilder = new AggregatorFactories.Builder()
          aggregations
            .getAggregatorFactories.asScala
            .foreach {
              case f: ValuesSourceAggregationBuilder[_] if notAllowedFields.find(s => s.value == f.field()).isDefined =>
                aggregatorFactoryBuilder.addAggregator(f.field(s"${f.field()}_${UUID.randomUUID().toString}"))
              case f =>
                aggregatorFactoryBuilder.addAggregator(f)
            }
          modifyBuilder(aggregatorFactoryBuilder)
      }
    }

    def hasScriptFields: Boolean = Option(builder.scriptFields()).exists(_.size() > 0)

    def fieldsUsageInQuery: RequestFieldsUsage = {
      Option(builder.query()) match {
        case None => NotUsingFields
        case Some(query) => query.fieldsUsage
      }
    }

    def fieldsUsageInAggregations: RequestFieldsUsage = {
      Option(builder.aggregations()) match {
        case None =>
          NotUsingFields
        case Some(aggregations) =>
          NonEmptyList
            .fromList {
              aggregations
                .getAggregatorFactories.asScala
                .flatMap {
                  case builder: ValuesSourceAggregationBuilder[_] => Option(builder.field()) :: Nil
                  case _ => Nil
                }
                .flatten
                .map(UsedField.apply)
                .toList
            }
            .map(UsingFields.apply)
            .getOrElse(NotUsingFields)
      }
    }
  }
}
