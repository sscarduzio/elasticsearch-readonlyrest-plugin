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
import org.elasticsearch.action.IndicesRequest.Replaceable
import org.elasticsearch.action.{ActionRequest, IndicesRequest}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.*
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{
  CannotExtractFields,
  NotUsingFields,
  UsedField,
  UsingFields
}
import tech.beshu.ror.accesscontrol.domain.{Action, ClusterIndexName, FieldLevelSecurity, Filter, RequestedIndex}
import tech.beshu.ror.accesscontrol.request.{TermsEnumFieldAction, TermsEnumRequestFieldsSupport}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.handler.response.FLSContextHeaderHandler
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.util.{Failure, Success, Try}

class TermsEnumEsRequestContext private (
    actionRequest: ActionRequest with Replaceable,
    esContext: EsContext,
    aclContext: AccessControlStaticContext,
    override val threadPool: ThreadPool
) extends BaseFilterableEsRequestContext[ActionRequest with Replaceable](
      actionRequest,
      esContext,
      aclContext,
      threadPool
    ) {

  override protected def requestFieldsUsage: RequestFieldsUsage = {
    Try(Option(on(actionRequest).call("field").get[String])) match {
      case Success(Some(value)) => UsingFields(NonEmptyList.one(UsedField(value)))
      case Success(None)        => NotUsingFields
      case Failure(_)           => CannotExtractFields
    }
  }

  override protected def requestedIndicesFrom(
      request: ActionRequest with Replaceable
  ): Set[RequestedIndex[ClusterIndexName]] = {
    request.asInstanceOf[IndicesRequest].indices.asSafeSet.flatMap(RequestedIndex.fromString)
  }

  override protected def update(
      request: ActionRequest with Replaceable,
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      filter: Option[Filter],
      fieldLevelSecurity: Option[FieldLevelSecurity]
  ): ModificationResult = {
    filter match {
      case Some(_) =>
        logger.debug(
          "a 'filter' rule was matched for the '_terms_enum' request, but document level security cannot be " +
            "applied to it, so we have to interrupt the request processing"
        )
        ShouldBeInterrupted
      case None =>
        request.indices(filteredRequestedIndices.stringify: _*)
        applyFieldLevelSecurity(fieldLevelSecurity)
    }
  }

  private def applyFieldLevelSecurity(fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult = {
    TermsEnumRequestFieldsSupport.fieldActionFor(fieldLevelSecurity) match {
      case TermsEnumFieldAction.NoChange =>
        Modified
      case TermsEnumFieldAction.ApplyLuceneRestrictions(restrictions) =>
        FLSContextHeaderHandler.addContextHeader(threadPool, restrictions)
        Modified
      case TermsEnumFieldAction.ObfuscateFieldWith(value) =>
        obfuscateRequestedField(value)
    }
  }

  private def obfuscateRequestedField(obfuscatedValue: String): ModificationResult = {
    Try(on(actionRequest).set("field", obfuscatedValue)) match {
      case Success(_) =>
        Modified
      case Failure(ex) =>
        logger.error("Cannot modify the requested field of a '_terms_enum' request. Please report the issue.", ex)
        ShouldBeInterrupted
    }
  }

}

object TermsEnumEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[TermsEnumEsRequestContext] = {
    (arg.esContext.action == Action.EsAction.termsEnumAction, arg.esContext.actionRequest) match {
      case (true, request: Replaceable) =>
        Some(new TermsEnumEsRequestContext(request, arg.esContext, arg.aclContext, arg.threadPool))
      case _ =>
        None
    }
  }

}
