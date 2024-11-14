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
package tech.beshu.ror.es.handler.request.context.types

import cats.data.NonEmptyList
import cats.implicits.*
import monix.eval.Task
import org.elasticsearch.action.{ActionRequest, ActionResponse, CompositeIndicesRequest}
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.*
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, Filter, RequestedIndex}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{CannotModify, UpdateResponse}
import tech.beshu.ror.es.handler.response.FLSContextHeaderHandler
import tech.beshu.ror.es.utils.esql.EsqlRequestHelper
import tech.beshu.ror.exceptions.SecurityPermissionException
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

class EsqlIndicesEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
                                         esContext: EsContext,
                                         aclContext: AccessControlStaticContext,
                                         clusterService: RorClusterService,
                                         override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[ActionRequest with CompositeIndicesRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.NotUsingFields

  private lazy val sqlIndicesExtractResult = EsqlRequestHelper.indicesFrom(actionRequest) match {
    case result@Right(_) => result
    case result@Left(EsqlRequestHelper.IndicesError.ParsingException) => result
    case Left(EsqlRequestHelper.IndicesError.UnexpectedException(ex)) =>
      throw RequestSeemsToBeInvalid[CompositeIndicesRequest](s"Cannot extract SQL indices from ${actionRequest.getClass.show}", ex)
  }

  override protected def requestedIndicesFrom(request: ActionRequest with CompositeIndicesRequest): Set[RequestedIndex[ClusterIndexName]] = {
    sqlIndicesExtractResult.map(_.indices.flatMap(RequestedIndex.fromString)) match {
      case Right(indices) => indices
      case Left(_) => Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))
    }
  }

  override protected def update(request: ActionRequest with CompositeIndicesRequest,
                                filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                filter: Option[Filter],
                                fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult = {
    val result: Either[EsqlRequestHelper.ModificationError, UpdateResponse] = for {
      _ <- modifyRequestIndices(request, filteredRequestedIndices)
      _ <- Right(applyFieldLevelSecurityTo(request, fieldLevelSecurity))
      _ <- Right(applyFilterTo(request, filter))
    } yield {
      UpdateResponse { response =>
        Task.delay {
          applyFieldLevelSecurityTo(response, fieldLevelSecurity) match {
            case Right(modifiedResponse) =>
              modifiedResponse
            case Left(EsqlRequestHelper.ModificationError.UnexpectedException(ex)) =>
              throw new SecurityPermissionException("Cannot apply field level security to the SQL response", ex)
          }
        }
      }
    }
    result.fold(
      error => {
        logger.error(s"[${id.show}] Cannot modify SQL indices of incoming request; error=${error.show}")
        CannotModify
      },
      identity
    )
  }

  private def modifyRequestIndices(request: ActionRequest with CompositeIndicesRequest,
                                   indices: NonEmptyList[RequestedIndex[ClusterIndexName]]): Either[EsqlRequestHelper.ModificationError, CompositeIndicesRequest] = {
    sqlIndicesExtractResult match {
      case Right(sqlIndices) =>
        val indicesStrings = indices.stringify.toCovariantSet
        if (indicesStrings != sqlIndices.indices) {
          EsqlRequestHelper.modifyIndicesOf(request, sqlIndices, indicesStrings)
        } else {
          Right(request)
        }
      case Left(_) =>
        logger.debug(s"[${id.show}] Cannot parse SQL statement - we can pass it though, because ES is going to reject it")
        Right(request)
    }
  }

  private def applyFieldLevelSecurityTo(request: ActionRequest with CompositeIndicesRequest,
                                        fieldLevelSecurity: Option[FieldLevelSecurity]) = {
    fieldLevelSecurity match {
      case Some(definedFields) =>
        definedFields.strategy match {
          case FlsAtLuceneLevelApproach =>
            FLSContextHeaderHandler.addContextHeader(threadPool, definedFields.restrictions, id)
            request
          case BasedOnBlockContextOnly.NotAllowedFieldsUsed(_) | BasedOnBlockContextOnly.EverythingAllowed =>
            request
        }
      case None =>
        request
    }
  }

  private def applyFieldLevelSecurityTo(response: ActionResponse,
                                        fieldLevelSecurity: Option[FieldLevelSecurity]) = {
    fieldLevelSecurity match {
      case Some(fls) =>
        EsqlRequestHelper.modifyResponseAccordingToFieldLevelSecurity(response, fls)
      case None =>
        Right(response)
    }
  }

  private def applyFilterTo(request: ActionRequest with CompositeIndicesRequest,
                            filter: Option[Filter]) = {
    import tech.beshu.ror.es.handler.request.SearchRequestOps.*
    Option(on(request).call("filter").get[QueryBuilder])
      .wrapQueryBuilder(filter)
      .foreach { qb => on(request).set("filter", qb) }
    request
  }
}

object EsqlIndicesEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest): Option[EsqlIndicesEsRequestContext] = {
    if (arg.esContext.channel.request().path().startsWith("/_query")) {
      Some(new EsqlIndicesEsRequestContext(
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