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
import org.elasticsearch.action.{ActionRequest, ActionResponse, CompositeIndicesRequest}
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.*
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{
  BasedOnBlockContextOnly,
  FlsAtLuceneLevelApproach
}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, Filter, RequestedIndex}
import tech.beshu.ror.es.esql.{ClassificationError, QueryRejection, RequestClassification}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.UpdateResponse
import tech.beshu.ror.es.handler.response.FLSContextHeaderHandler
import tech.beshu.ror.es.utils.EsqlRequestHelper
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

class EsqlIndicesEsRequestContext private (
    actionRequest: ActionRequest with CompositeIndicesRequest,
    esContext: EsContext,
    aclContext: AccessControlStaticContext,
    override val threadPool: ThreadPool
) extends BaseFilterableEsRequestContext[ActionRequest with CompositeIndicesRequest](
      actionRequest,
      esContext,
      aclContext,
      threadPool
    ) {

  override protected def requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.NotUsingFields

  private lazy val requestClassification = EsqlRequestHelper.classifyEsqlRequest(actionRequest)

  override protected def requestedIndicesFrom(
      request: ActionRequest with CompositeIndicesRequest
  ): Set[RequestedIndex[ClusterIndexName]] = {
    requestClassification match {
      case Right(classification @ RequestClassification.IndicesRelated(_)) =>
        classification.requestedIndices
      case Right(RequestClassification.NonIndicesRelated) | Left(_) =>
        allIndices
    }
  }

  /** What a query ROR could not read the index lists of has to be taken to request. */
  private val allIndices: Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

  override protected def update(
      request: ActionRequest with CompositeIndicesRequest,
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      filter: Option[Filter],
      fieldLevelSecurity: Option[FieldLevelSecurity]
  ): ModificationResult = {
    modifyRequestIndices(request, filteredRequestedIndices) match {
      case Right(_) =>
        applyFieldLevelSecurityTo(request, fieldLevelSecurity)
        applyFilterTo(request, filter)
        UpdateResponse.sync { response => applyFieldLevelSecurityTo(response, fieldLevelSecurity) }
      case Left(rejection) =>
        logger.warn(rejection.show)
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def modifyRequestIndices(
      request: ActionRequest with CompositeIndicesRequest,
      filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Either[QueryRejection, Unit] = {
    requestClassification match {
      case Right(RequestClassification.NonIndicesRelated) =>
        Right(())
      case Right(classification @ RequestClassification.IndicesRelated(indexLists)) =>
        if (aclLeftIndicesAlone(filteredIndices, classification.requestedIndices)) Right(())
        else EsqlRequestHelper.modifyIndicesOf(request, indexLists, filteredIndices)
      case Left(ClassificationError.NotParsable(cause)) =>
        logger.debug("Cannot parse the ES|QL statement - we can pass it through, because ES will reject it too", cause)
        Right(())
      case Left(ClassificationError.CannotReadIndexList(failure)) =>
        if (aclLeftIndicesAlone(filteredIndices, allIndices)) Right(())
        else Left(QueryRejection.CannotReadIndexList(failure))
    }
  }

  /**
   * A query ROR cannot rewrite is only a problem when it would have had to. Left to run against the indices it
   * already asked for, an ES|QL query ROR cannot read is no worse off than before ROR could read any of them.
   */
  private def aclLeftIndicesAlone(
      filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      requestedIndices: Set[RequestedIndex[ClusterIndexName]]
  ): Boolean = filteredIndices.toList.toCovariantSet == requestedIndices

  private def applyFieldLevelSecurityTo(
      request: ActionRequest with CompositeIndicesRequest,
      fieldLevelSecurity: Option[FieldLevelSecurity]
  ) = {
    fieldLevelSecurity match {
      case Some(definedFields) =>
        definedFields.strategy match {
          case FlsAtLuceneLevelApproach =>
            FLSContextHeaderHandler.addContextHeader(threadPool, definedFields.restrictions)
            request
          case BasedOnBlockContextOnly.NotAllowedFieldsUsed(_) | BasedOnBlockContextOnly.EverythingAllowed =>
            request
        }
      case None =>
        request
    }
  }

  private def applyFieldLevelSecurityTo(response: ActionResponse, fieldLevelSecurity: Option[FieldLevelSecurity]) = {
    fieldLevelSecurity match {
      case Some(fls) => EsqlRequestHelper.modifyResponseAccordingToFieldLevelSecurity(response, fls)
      case None      => response
    }
  }

  private def applyFilterTo(request: ActionRequest with CompositeIndicesRequest, filter: Option[Filter]) = {
    import tech.beshu.ror.es.handler.request.SearchRequestOps.*
    Option(on(request).call("filter").get[QueryBuilder])
      .wrapQueryBuilder(filter)
      .foreach { qb => on(request).set("filter", qb) }
    request
  }

}

object EsqlIndicesEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[EsqlIndicesEsRequestContext] = {
    if (arg.esContext.channel.restRequest.path.isEsqlQueryPath) {
      Some(
        new EsqlIndicesEsRequestContext(
          arg.esContext.actionRequest.asInstanceOf[ActionRequest with CompositeIndicesRequest],
          arg.esContext,
          arg.aclContext,
          arg.threadPool
        )
      )
    } else {
      None
    }
  }

}
