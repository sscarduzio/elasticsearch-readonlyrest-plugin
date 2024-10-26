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
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.indices.alias.get.{GetAliasesRequest, GetAliasesResponse}
import org.elasticsearch.cluster.metadata.{AliasMetadata, DataStreamAlias}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{AliasRequestBlockContext, RandomIndexBasedOnBlockContextIndices, RequestedIndex}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.show.logs.*
import tech.beshu.ror.accesscontrol.utils.IndicesListOps.*
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted, UpdateResponse}
import tech.beshu.ror.es.handler.request.context.types.utils.FilterableAliasesMap.*
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps.*

import java.util.List as JList

class GetAliasesEsRequestContext(actionRequest: GetAliasesRequest,
                                 esContext: EsContext,
                                 aclContext: AccessControlStaticContext,
                                 clusterService: RorClusterService,
                                 override val threadPool: ThreadPool)
  extends BaseEsRequestContext[AliasRequestBlockContext](esContext, clusterService)
    with EsRequest[AliasRequestBlockContext] {

  override val initialBlockContext: AliasRequestBlockContext = AliasRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    List.empty,
    {
      val indices = aliasesFrom(actionRequest)
      logger.debug(s"[${id.show}] Discovered aliases: ${indices.show}")
      indices
    },
    {
      val indices = indicesFrom(actionRequest)
      logger.debug(s"[${id.show}] Discovered indices: ${indices.show}")
      indices
    },
  )

  override protected def modifyRequest(blockContext: AliasRequestBlockContext): ModificationResult = {
    val result = for {
      indices <- NonEmptyList.fromList(blockContext.indices.toList)
      aliases <- NonEmptyList.fromList(blockContext.aliases.toList)
    } yield (indices, aliases)
    result match {
      case Some((indices, aliases)) =>
        updateIndices(actionRequest, indices)
        updateAliases(actionRequest, aliases)
        UpdateResponse(updateAliasesResponse(aliases, _))
      case None =>
        logger.error(s"[${id.show}] At least one alias and one index has to be allowed. " +
          s"Found allowed indices: [${blockContext.indices.show}]." +
          s"Found allowed aliases: [${blockContext.aliases.map(_.show).mkString(",")}]")
        ShouldBeInterrupted
    }
  }

  override def modifyWhenIndexNotFound: ModificationResult = {
    if (aclContext.doesRequirePassword) {
      val nonExistentIndex = initialBlockContext.randomNonexistentIndex()
      if (nonExistentIndex.hasWildcard) {
        val nonExistingIndices = NonEmptyList
          .fromList(initialBlockContext.nonExistingIndicesFromInitialIndices().toList)
          .getOrElse(NonEmptyList.of(nonExistentIndex))
        updateIndices(actionRequest, nonExistingIndices)
        Modified
      } else {
        ShouldBeInterrupted
      }
    } else {
      updateIndices(actionRequest, NonEmptyList.of(initialBlockContext.randomNonexistentIndex()))
      Modified
    }
  }

  override def modifyWhenAliasNotFound: ModificationResult = {
    if (aclContext.doesRequirePassword) {
      val nonExistentAlias = initialBlockContext.aliases.toList.randomNonexistentIndex()
      if (nonExistentAlias.hasWildcard) {
        val nonExistingAliases = NonEmptyList
          .fromList(initialBlockContext.aliases.map(_.randomNonexistentIndex()).toList)
          .getOrElse(NonEmptyList.of(nonExistentAlias))
        updateAliases(actionRequest, nonExistingAliases)
        Modified
      } else {
        ShouldBeInterrupted
      }
    } else {
      updateAliases(actionRequest, NonEmptyList.of(initialBlockContext.aliases.toList.randomNonexistentIndex()))
      Modified
    }
  }

  private def updateIndices(request: GetAliasesRequest, indices: NonEmptyList[RequestedIndex]): Unit = {
    request.indices(indices.stringify: _*)
  }

  private def updateAliases(request: GetAliasesRequest, aliases: NonEmptyList[RequestedIndex]): Unit = {
    if (isRequestedEmptyAliasesSet(request)) {
      // we don't need to do anything
    } else {
      request.aliases(aliases.stringify: _*)
    }
  }

  private def updateAliasesResponse(allowedAliases: NonEmptyList[RequestedIndex],
                                    response: ActionResponse): Task[ActionResponse] = {
    val (aliases, streams) = response match {
      case aliasesResponse: GetAliasesResponse =>
        (
          aliasesResponse.getAliases.filterOutNotAllowedAliases(allowedAliases),
          aliasesResponse.getDataStreamAliases
        )
      case other =>
        logger.error(s"${id.show} Unexpected response type - expected: [${classOf[GetAliasesResponse].getSimpleName}], was: [${other.getClass.getSimpleName}]")
        (
          Map.asEmptyJavaMap[String, JList[AliasMetadata]],
          Map.asEmptyJavaMap[String, JList[DataStreamAlias]]
        )
    }
    Task.now(new GetAliasesResponse(aliases, streams))
  }

  private def indicesFrom(request: GetAliasesRequest) = {
    indicesOrWildcard(request.indices().asSafeSet.flatMap(RequestedIndex.fromString))
  }

  private def aliasesFrom(request: GetAliasesRequest) = {
    indicesOrWildcard(rawRequestAliasesSet(request).flatMap(RequestedIndex.fromString))
  }

  private def isRequestedEmptyAliasesSet(request: GetAliasesRequest) = {
    rawRequestAliasesSet(request).isEmpty
  }

  private def rawRequestAliasesSet(request: GetAliasesRequest) = request.aliases().asSafeSet
}
