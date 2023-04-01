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
import cats.implicits._
import monix.eval.Task
import org.elasticsearch.action.{ActionRequest, ActionResponse, CompositeIndicesRequest}
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect._
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, Filter}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{CannotModify, UpdateResponse}
import tech.beshu.ror.es.handler.response.FLSContextHeaderHandler
import tech.beshu.ror.es.utils.SqlRequestHelper
import tech.beshu.ror.exceptions.SecurityPermissionException

class SqlIndicesEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
                                         esContext: EsContext,
                                         aclContext: AccessControlStaticContext,
                                         clusterService: RorClusterService,
                                         override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[ActionRequest with CompositeIndicesRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.NotUsingFields

  private lazy val sqlIndicesExtractResult = SqlRequestHelper.indicesFrom(actionRequest) match {
    case result@Right(_) => result
    case result@Left(SqlRequestHelper.IndicesError.ParsingException) => result
    case Left(SqlRequestHelper.IndicesError.UnexpectedException(ex)) =>
      throw RequestSeemsToBeInvalid[CompositeIndicesRequest](s"Cannot extract SQL indices from ${actionRequest.getClass.getName}", ex)
  }

  override protected def indicesFrom(request: ActionRequest with CompositeIndicesRequest): Set[ClusterIndexName] = {
    sqlIndicesExtractResult.map(_.indices.flatMap(ClusterIndexName.fromString)) match {
      case Right(indices) => indices
      case Left(_) => Set(ClusterIndexName.Local.wildcard)
    }
  }

  override protected def update(request: ActionRequest with CompositeIndicesRequest,
                                indices: NonEmptyList[ClusterIndexName],
                                filter: Option[Filter],
                                fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult = {
    val result = for {
      _ <- modifyRequestIndices(request, indices)
      _ <- Right(applyFieldLevelSecurityTo(request, fieldLevelSecurity))
      _ <- Right(applyFilterTo(request, filter))
    } yield {
      UpdateResponse { response =>
        Task.delay {
          applyFieldLevelSecurityTo(response, fieldLevelSecurity) match {
            case Right(modifiedResponse) =>
              modifiedResponse
            case Left(SqlRequestHelper.ModificationError.UnexpectedException(ex)) =>
              throw new SecurityPermissionException("Cannot apply field level security to the SQL response", ex)
          }
        }
      }
    }
    result.fold(
      error => {
        logger.error(s"[${id.show}] Cannot modify SQL indices of incoming request; error=$error")
        CannotModify
      },
      identity
    )
  }

  private def modifyRequestIndices(request: ActionRequest with CompositeIndicesRequest,
                                   indices: NonEmptyList[ClusterIndexName]): Either[SqlRequestHelper.ModificationError, CompositeIndicesRequest] = {
    sqlIndicesExtractResult match {
      case Right(sqlIndices) =>
        val indicesStrings = indices.map(_.stringify).toList.toSet
        if (indicesStrings != sqlIndices.indices) {
          SqlRequestHelper.modifyIndicesOf(request, sqlIndices, indicesStrings)
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
        SqlRequestHelper.modifyResponseAccordingToFieldLevelSecurity(response, fls)
      case None =>
        Right(response)
    }
  }

  private def applyFilterTo(request: ActionRequest with CompositeIndicesRequest,
                            filter: Option[Filter]) = {
    import tech.beshu.ror.es.handler.request.SearchRequestOps._
    Option(on(request).call("filter").get[QueryBuilder])
      .wrapQueryBuilder(filter)
      .foreach { qb => on(request).set("filter", qb) }
    request
  }
}

object SqlIndicesEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest): Option[SqlIndicesEsRequestContext] = {
    if (arg.esContext.channel.request().path().startsWith("/_sql")) {
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