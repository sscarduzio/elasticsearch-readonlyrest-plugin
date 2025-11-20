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
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest
import org.elasticsearch.cluster.routing.allocation.command.*
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.syntax.*

import scala.jdk.CollectionConverters.*

class ClusterRerouteEsRequestContext(actionRequest: ClusterRerouteRequest,
                                     esContext: EsContext,
                                     aclContext: AccessControlStaticContext,
                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ClusterRerouteRequest](actionRequest, esContext, aclContext, threadPool) {

  override protected def requestedIndicesFrom(request: ClusterRerouteRequest): Set[RequestedIndex[ClusterIndexName]] = {
    request
      .getCommands.commands().asScala
      .flatMap(indexFrom)
      .toCovariantSet
  }

  override protected def update(request: ClusterRerouteRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    val modifiedCommands = request
      .getCommands.commands().asScala.toSeq
      .map(modifiedCommand(_, filteredIndices))
    request.commands(new AllocationCommands(modifiedCommands: _*))
    Modified
  }

  private def modifiedCommand(command: AllocationCommand, filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]) = {
    val indexFromCommand = indexFrom(command)
    indexFromCommand match {
      case None => command
      case Some(index) if filteredIndices.exists(_ === index) => command
      case Some(_) => setNonExistentIndexFor(command)
    }
  }

  private def indexFrom(command: AllocationCommand): Option[RequestedIndex[ClusterIndexName]] = {
    val indexNameStr = command match {
      case c: CancelAllocationCommand => c.index()
      case c: MoveAllocationCommand => c.index()
      case c: AllocateEmptyPrimaryAllocationCommand => c.index()
      case c: AllocateReplicaAllocationCommand => c.index()
      case c: AllocateStalePrimaryAllocationCommand => c.index()
    }
    RequestedIndex.fromString(indexNameStr)
  }

  private def setNonExistentIndexFor(command: AllocationCommand) = {
    val randomIndex = indexFrom(command)
        .map(_.name.randomNonexistentIndex())
        .getOrElse(ClusterIndexName.Local.randomNonexistentIndex())
    command match {
      case c: CancelAllocationCommand =>
        new CancelAllocationCommand(randomIndex.stringify, c.shardId(), c.node(), c.allowPrimary())
      case c: MoveAllocationCommand =>
        new MoveAllocationCommand(randomIndex.stringify, c.shardId(), c.fromNode(), c.toNode)
      case c: AllocateEmptyPrimaryAllocationCommand =>
        new AllocateEmptyPrimaryAllocationCommand(randomIndex.stringify, c.shardId(), c.node(), c.acceptDataLoss())
      case c: AllocateReplicaAllocationCommand =>
        new AllocateReplicaAllocationCommand(randomIndex.stringify, c.shardId(), c.node())
      case c: AllocateStalePrimaryAllocationCommand =>
        new AllocateStalePrimaryAllocationCommand(randomIndex.stringify, c.shardId(), c.node(), c.acceptDataLoss())
    }
  }
}
