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
import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Filter, IndexName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{CannotModify, Modified}
import tech.beshu.ror.es.request.{FLSContextHeaderHandler, RequestSeemsToBeInvalid}
import tech.beshu.ror.es.utils.SqlRequestHelper

import scala.util.{Failure, Success}

class SqlIndicesEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
                                         esContext: EsContext,
                                         aclContext: AccessControlStaticContext,
                                         clusterService: RorClusterService,
                                         override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[ActionRequest with CompositeIndicesRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.CannotExtractFields

  private lazy val sqlIndices = SqlRequestHelper
    .indicesFrom(actionRequest)
    .getOrElse(throw RequestSeemsToBeInvalid[CompositeIndicesRequest](s"Cannot extract SQL indices from ${actionRequest.getClass.getName}"))

  override protected def indicesFrom(request: ActionRequest with CompositeIndicesRequest): Set[IndexName] =
    sqlIndices.indices.flatMap(IndexName.fromString)

  /** fixme: filter is not applied.
      If there is no way to apply filter to request (see e.g. SearchEsRequestContext),
      it can be handled just as in GET/MGET (see e.g. GetEsRequestContext) using ModificationResult.UpdateResponse.
   **/
  override protected def update(request: ActionRequest with CompositeIndicesRequest,
                                indices: NonEmptyList[IndexName],
                                filter: Option[Filter],
                                fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult = {
    val indicesStrings = indices.map(_.value.value).toList.toSet
    if (indicesStrings != sqlIndices.indices) {
      SqlRequestHelper.modifyIndicesOf(request, sqlIndices, indicesStrings) match {
        case Success(_) =>
          applyFieldLevelSecurity(request, fieldLevelSecurity)
          Modified
        case Failure(ex) =>
          logger.error("Cannot modify SQL indices of incoming request", ex)
          CannotModify
      }
    } else {
      applyFieldLevelSecurity(request, fieldLevelSecurity)
      Modified
    }
  }

  /**  TODO fls works because 'CannotExtractFields' value is always returned for sql request,
   * so fls at lucene level is used. Maybe there is a way to modify used fields in query (see e.g. SearchEsRequestContext)
   * and abandon lucene approach.
   */
  private def applyFieldLevelSecurity(request: ActionRequest with CompositeIndicesRequest,
                                      fieldLevelSecurity: Option[FieldLevelSecurity]) =  {
    fieldLevelSecurity match {
      case Some(definedFields) =>
        definedFields.strategy match {
          case FlsAtLuceneLevelApproach =>
            FLSContextHeaderHandler.addContextHeader(threadPool, definedFields.restrictions, id)
          case BasedOnBlockContextOnly.NotAllowedFieldsUsed(_) | BasedOnBlockContextOnly.EverythingAllowed =>
            request
        }
      case None =>
        request
    }
  }
}

object SqlIndicesEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest): Option[SqlIndicesEsRequestContext] = {
    if (arg.esContext.channel.request().path().startsWith("/_xpack/sql")) {
      Some(new SqlIndicesEsRequestContext(
        arg.esContext.actionRequest.asInstanceOf[ActionRequest with CompositeIndicesRequest],
        arg.esContext,
        arg.aclContext,
        arg.clusterService,
        arg.threadPool
      ))
    } else {
      None
    }
  }
}