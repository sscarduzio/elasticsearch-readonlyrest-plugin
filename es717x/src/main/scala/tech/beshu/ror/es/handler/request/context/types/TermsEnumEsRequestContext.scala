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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, Filter, RequestedIndex}
import tech.beshu.ror.accesscontrol.request.{TermsEnumRequestAdapter, TermsEnumRequestFieldsSupport}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.response.FLSContextHeaderHandler
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class TermsEnumEsRequestContext(
    actionRequest: ActionRequest with Replaceable,
    esContext: EsContext,
    aclContext: AccessControlStaticContext,
    override val threadPool: ThreadPool
) extends BaseFilterableEsRequestContext[ActionRequest with Replaceable](
      actionRequest,
      esContext,
      aclContext,
      threadPool
    )
    with TermsEnumRequestAdapter {

  override def requestedField: Option[String] = Option(on(actionRequest).call("field").get[String])

  override def modifyRequestedField(newField: String): Unit = on(actionRequest).set("field", newField)

  override protected def requestFieldsUsage: RequestFieldsUsage = {
    TermsEnumRequestFieldsSupport.requestFieldsUsageFor(this)
  }

  override protected def requestedIndicesFrom(
      request: ActionRequest with Replaceable
  ): Set[RequestedIndex[ClusterIndexName]] = {
    request.asInstanceOf[IndicesRequest].indices.asSafeSet.flatMap(RequestedIndex.fromString)
  }

  // A 'filter' rule never reaches here: BaseEsRequestContext.isAllowedForDLS is false for the '_terms_enum' action,
  // so FilterRule already rejects the match for any block that has a 'filter' rule configured.
  override protected def update(
      request: ActionRequest with Replaceable,
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      filter: Option[Filter],
      fieldLevelSecurity: Option[FieldLevelSecurity]
  ): ModificationResult = {
    request.indices(filteredRequestedIndices.stringify: _*)
    TermsEnumRequestFieldsSupport.fieldsRestrictionsToApply(this, fieldLevelSecurity).foreach { restrictions =>
      FLSContextHeaderHandler.addContextHeader(threadPool, restrictions)
    }
    Modified
  }

}
