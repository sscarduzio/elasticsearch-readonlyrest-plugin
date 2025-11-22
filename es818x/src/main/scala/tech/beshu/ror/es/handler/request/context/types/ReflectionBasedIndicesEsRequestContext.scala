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
import com.google.common.collect.Sets
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ReflecUtils

import java.util.List as JList
import scala.jdk.CollectionConverters.*
import scala.util.Try

class ReflectionBasedIndicesEsRequestContext private(actionRequest: ActionRequest,
                                                     requestedIndices: Set[RequestedIndex[ClusterIndexName]],
                                                     esContext: EsContext,
                                                     aclContext: AccessControlStaticContext,
                                                     clusterService: RorClusterService,
                                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestedIndicesFrom(request: ActionRequest): Set[RequestedIndex[ClusterIndexName]] = requestedIndices

  override protected def update(request: ActionRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    if (tryUpdate(actionRequest, filteredIndices)) Modified
    else {
      logger.error(s"Cannot update ${actionRequest.getClass.show} request. We're using reflection to modify the request indices and it fails. Please, report the issue.")
      ShouldBeInterrupted
    }
  }

  private def tryUpdate(actionRequest: ActionRequest, indices: NonEmptyList[RequestedIndex[ClusterIndexName]]) = {
    // Optimistic reflection attempt
    ReflecUtils.setIndices(
      actionRequest,
      Sets.newHashSet("index", "indices", "setIndex", "setIndices"),
      indices.stringify.toSet.asJava
    )
  }
}

object ReflectionBasedIndicesEsRequestContext extends Logging {

  def unapply(arg: ReflectionBasedActionRequest): Option[ReflectionBasedIndicesEsRequestContext] = {
    requestedIndicesFrom(arg.esContext.actionRequest)
      .map(new ReflectionBasedIndicesEsRequestContext(arg.esContext.actionRequest, _, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
  }

  private def requestedIndicesFrom(request: ActionRequest) = {
    getIndicesUsingMethod(request, methodName = "getIndices")
      .orElse(getIndicesUsingField(request, fieldName = "indices"))
      .orElse(getIndexUsingMethod(request, methodName = "getIndex"))
      .orElse(getIndexUsingField(request, fieldName = "index"))
      .map(indices => indices.toList.toCovariantSet.flatMap(RequestedIndex.fromString))
  }

  private def getIndicesUsingMethod(request: ActionRequest, methodName: String) = {
    callMethod[JList[String]](request, methodName)
      .map { indices =>
        NonEmptyList.fromFoldable {
          Option(indices).toList.flatMap(_.asScala)
        }
      }
      .getOrElse(None)
  }

  private def getIndicesUsingField(request: ActionRequest, fieldName: String) = {
    getFieldValue[JList[String]](request, fieldName)
      .map { indices =>
        NonEmptyList.fromFoldable {
          Option(indices).toList.flatMap(_.asScala)
        }
      }
      .getOrElse(None)
  }

  private def getIndexUsingField(request: ActionRequest, fieldName: String) = {
    getFieldValue[String](request, fieldName)
      .map(index => NonEmptyList.fromFoldable(Option(index)))
      .getOrElse(None)
  }

  private def getIndexUsingMethod(request: ActionRequest, methodName: String) =  {
    callMethod[String](request, methodName)
      .map(index => NonEmptyList.fromFoldable(Option(index)))
      .getOrElse(None)
  }

  private def callMethod[RESULT](obj: AnyRef, methodName: String) = {
    import org.joor.Reflect.*
    Try(on(obj).call(methodName).get[RESULT])
  }

  private def getFieldValue[RESULT](obj: AnyRef, fieldName: String) = {
    import org.joor.Reflect.*
    Try(on(obj).get[RESULT](fieldName))
  }
}
