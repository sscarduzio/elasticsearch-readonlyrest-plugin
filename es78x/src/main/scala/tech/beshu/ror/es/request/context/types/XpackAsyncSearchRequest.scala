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
package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, IndexName}
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.queries.QueryFieldsUsage._
import tech.beshu.ror.es.request.queries.QueryFieldsUsage.instances._
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

class XpackAsyncSearchRequest private(actionRequest: ActionRequest,
                                      esContext: EsContext,
                                      aclContext: AccessControlStaticContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val searchRequest = searchRequestFrom(actionRequest)

  override protected def indicesFrom(request: ActionRequest): Set[domain.IndexName] = {
    searchRequest
      .indices.asSafeSet
      .flatMap(IndexName.fromString)
  }

  override protected def update(request: ActionRequest,
                                indices: NonEmptyList[domain.IndexName],
                                filter: Option[domain.Filter],
                                fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult = {
    optionallyDisableCaching(fieldLevelSecurity)
    searchRequest
      .applyFilterToQuery(filter)
      .indices(indices.toList.map(_.value.value): _*)
    Modified
  }

  override def requestFieldsUsage: RequestFieldsUsage = {
    Option(searchRequest.source().scriptFields()) match {
      case Some(scriptFields) if scriptFields.size() > 0 =>
        RequestFieldsUsage.CantExtractFields
      case _ =>
        checkQueryFields()
    }
  }

  private def checkQueryFields(): RequestFieldsUsage = {
    Option(searchRequest.source().query())
      .map(_.fieldsUsage)
      .getOrElse(RequestFieldsUsage.NotUsingFields)
  }
  private def searchRequestFrom(request: ActionRequest) = {
    Option(invokeMethodCached(request, request.getClass, "getSearchRequest"))
      .collect { case sr: SearchRequest => sr }
      .getOrElse(throw new RequestSeemsToBeInvalid[ActionRequest]("Cannot extract SearchRequest from SubmitAsyncSearchRequest request"))
  }

  private def optionallyDisableCaching(fieldLevelSecurity: Option[FieldLevelSecurity]): Unit = {
    fieldLevelSecurity.map(_.strategy) match {
      case Some(FieldLevelSecurity.Strategy.LuceneContextHeaderApproach) =>
        logger.debug("ACL uses context header for fields rule, will disable request cache for SearchRequest")
        searchRequest.requestCache(false)
      case _ =>
    }
  }
}

object XpackAsyncSearchRequest {

  def unapply(arg: ReflectionBasedActionRequest): Option[XpackAsyncSearchRequest] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("SubmitAsyncSearchRequest")) {
      Some(new XpackAsyncSearchRequest(arg.esContext.actionRequest, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}
