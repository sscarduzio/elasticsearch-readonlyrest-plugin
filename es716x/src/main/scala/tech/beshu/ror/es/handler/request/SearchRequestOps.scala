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
import cats.syntax.show._
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.query.{AbstractQueryBuilder, QueryBuilder, QueryBuilders}
import org.elasticsearch.search.aggregations.AggregatorFactories
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregationBuilder
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{NotUsingFields, UsedField, UsingFields}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Filter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.handler.request.queries.QueryFieldsUsage.instances._
import tech.beshu.ror.es.handler.request.queries.QueryFieldsUsage.{Ops => QueryFieldsUsageOps}
import tech.beshu.ror.es.handler.request.queries.QueryWithModifiableFields.instances._
import tech.beshu.ror.es.handler.request.queries.QueryWithModifiableFields.{Ops => QueryWithModifiableFieldsOps}
import tech.beshu.ror.es.handler.response.FLSContextHeaderHandler

import java.util.UUID
import scala.collection.JavaConverters._

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
                                                 filterQuery: QueryBuilder) = {
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
              modifyNotAllowedFieldsInQuery(notAllowedFields)
            case BasedOnBlockContextOnly.EverythingAllowed =>
              request
          }
        case None =>
          request
      }
    }

    def checkFieldsUsage(): RequestFieldsUsage = {
      Option(request.source()).flatMap(s => Option(s.scriptFields())) match {
        case Some(scriptFields) if scriptFields.size() > 0 =>
          RequestFieldsUsage.CannotExtractFields
        case _ =>
          checkQueryAndAggregationsFields()
      }
    }

    private def modifyNotAllowedFieldsInQuery(notAllowedFields: NonEmptyList[UsedField.SpecificField]) = {
      val currentQuery = request.source().query()
      val newQuery = currentQuery.handleNotAllowedFields(notAllowedFields)
      request.source().query(newQuery)
      Option(request.source()).foreach(s =>
          Option(s.aggregations())
            .map { aggs =>
              val builder = new AggregatorFactories.Builder()
              aggs
                .getAggregatorFactories.asScala
                .foreach {
                  case f: ValuesSourceAggregationBuilder[_] if notAllowedFields.find(s => s.value == f.field()).isDefined =>
                    builder.addAggregator(f.field(s"${f.field()}_${UUID.randomUUID().toString}"))
                  case f =>
                    builder.addAggregator(f)
                }
              import org.joor.Reflect._
              on(s).set("aggregations", builder)
            }
        )
      request
    }

    private def checkQueryAndAggregationsFields(): RequestFieldsUsage = {
      Option(request.source())
        .map { s =>
          val fieldsFromAggregations = Option(s.aggregations())
            .map(_
              .getAggregatorFactories.asScala
              .flatMap {
                case builder: ValuesSourceAggregationBuilder[_] => builder.field() :: Nil
                case _ => Nil
              }
              .map(UsedField.apply)
            )
            .toList
            .flatten
          val aggregationsRequestFieldsUsage = NonEmptyList.fromList(fieldsFromAggregations) match {
            case Some(fields) => UsingFields(fields)
            case None => NotUsingFields
          }
          val queryRequestFieldsUsage = Option(s.query()).map(_.fieldsUsage).getOrElse(NotUsingFields)
          val ll = (aggregationsRequestFieldsUsage, queryRequestFieldsUsage) match {
            case (UsingFields(f1), UsingFields(f2)) => UsingFields(f1 ::: f2)
            case (UsingFields(f1), _) => UsingFields(f1)
            case (_, UsingFields(f2)) => UsingFields(f2)
            case _ => NotUsingFields
          }
          ll: RequestFieldsUsage
        }
        .getOrElse(RequestFieldsUsage.NotUsingFields)
//      Option(request.source()).flatMap(s => Option(s.query()))
//        .map(_.fieldsUsage)
//        .getOrElse(RequestFieldsUsage.NotUsingFields)
    }

    private def disableCaching(requestId: RequestContext.Id) = {
      logger.debug(s"[${requestId.show}] ACL uses context header for fields rule, will disable request cache for SearchRequest")
      request.requestCache(false)
    }
  }
}
