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
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction.{ResolvedAlias, ResolvedIndex}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.*
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.RequestedIndex
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*

class ResolveIndexEsRequestContext(actionRequest: ResolveIndexAction.Request,
                                   esContext: EsContext,
                                   aclContext: AccessControlStaticContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ResolveIndexAction.Request](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestedIndicesFrom(request: ResolveIndexAction.Request): Set[RequestedIndex[ClusterIndexName]] = {
    request
      .indices().asSafeSet
      .flatMap(RequestedIndex.fromString)
      .orWildcardWhenEmpty
  }

  override protected def update(request: ResolveIndexAction.Request,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    request.indices(filteredIndices.stringify: _*)
    ModificationResult.UpdateResponse(resp => Task.delay(filterResponse(resp, allAllowedIndices)))
  }

  override def modifyWhenIndexNotFound: ModificationResult = {
    val randomNonExistingIndex = initialBlockContext.randomNonexistentIndex(_.filteredIndices)
    update(actionRequest, NonEmptyList.of(randomNonExistingIndex), NonEmptyList.of(randomNonExistingIndex.name))
    Modified
  }

  private def filterResponse(response: ActionResponse, indices: NonEmptyList[ClusterIndexName]): ActionResponse = {
    response match {
      case r: ResolveIndexAction.Response =>
        new ResolveIndexAction.Response(
          r.getIndices.asSafeList.flatMap(secureResolvedIndex(_, indices)).asJava,
          r.getAliases.asSafeList.flatMap(secureResolvedAlias(_, indices)).asJava,
          r.getDataStreams
        )
      case r => r
    }
  }

  private def secureResolvedIndex(resolvedIndex: ResolvedIndex, allowedIndices: NonEmptyList[ClusterIndexName]) = {
    if (isAllowed(resolvedIndex.getName, allowedIndices)) {
      val allowedResolvedAliases = resolvedIndex
        .getAliases.asSafeList
        .filter(isAllowed(_, allowedIndices))
      Some(createResolvedIndex(
        resolvedIndex.getName,
        allowedResolvedAliases,
        resolvedIndex.getAttributes,
        resolvedIndex.getDataStream
      ))
    } else {
      None
    }
  }

  private def secureResolvedAlias(resolvedAlias: ResolvedAlias, allowedIndices: NonEmptyList[ClusterIndexName]) = {
    if (isAllowed(resolvedAlias.getName, allowedIndices)) {
      val allowedResolvedIndices = resolvedAlias
        .getIndices.asSafeList
        .filter(isAllowed(_, allowedIndices))
      Some(createResolvedAlias(
        resolvedAlias.getName,
        allowedResolvedIndices
      ))
    } else {
      None
    }
  }

  private def isAllowed(aliasOrIndex: String, allowedIndices: NonEmptyList[ClusterIndexName]) = {
    val resolvedAliasOrIndexName = ClusterIndexName.Local.fromString(aliasOrIndex)
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName from ${aliasOrIndex.show}"))
    allowedIndices.exists(_.matches(resolvedAliasOrIndexName))
  }

  private def createResolvedIndex(index: String, aliases: List[String], attributes: Array[String], dataStream: String) = {
    onClass(classOf[ResolvedIndex])
      .create(index, aliases.toArray, attributes, dataStream)
      .get[ResolvedIndex]()
  }

  private def createResolvedAlias(alias: String, indices: List[String]) = {
    onClass(classOf[ResolvedAlias])
      .create(alias, indices.toArray)
      .get[ResolvedAlias]()
  }

}
