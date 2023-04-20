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
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest
import org.elasticsearch.cluster.routing.allocation.command._
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified

import scala.collection.JavaConverters._

class ClusterRerouteEsRequestContext(actionRequest: ClusterRerouteRequest,
                                     esContext: EsContext,
                                     aclContext: AccessControlStaticContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ClusterRerouteRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ClusterRerouteRequest): Set[ClusterIndexName] = {
    request
      .getCommands.commands().asScala
      .flatMap(indexFrom)
      .toSet
  }

  override protected def update(request: ClusterRerouteRequest,
                                filteredIndices: NonEmptyList[ClusterIndexName],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    val modifiedCommands = request
      .getCommands.commands().asScala.toList
      .map(modifiedCommand(_, filteredIndices))
    request.commands(new AllocationCommands(modifiedCommands: _*))
    Modified
  }

  private def modifiedCommand(command: AllocationCommand, allowedIndices: NonEmptyList[ClusterIndexName]) = {
    val indexFromCommand = indexFrom(command)
    indexFromCommand match {
      case None => command
      case Some(index) if allowedIndices.exists(_ === index) => command
      case Some(_) => setNonExistentIndexFor(command)
    }
  }

  private def indexFrom(command: AllocationCommand) = {
    val indexNameStr = command match {
      case c: CancelAllocationCommand => c.index()
      case c: MoveAllocationCommand => c.index()
      case c: AllocateEmptyPrimaryAllocationCommand => c.index()
      case c: AllocateReplicaAllocationCommand => c.index()
      case c: AllocateStalePrimaryAllocationCommand => c.index()
    }
    ClusterIndexName.fromString(indexNameStr)
  }

  private def setNonExistentIndexFor(command: AllocationCommand) = {
    val randomIndex = indexFrom(command)
        .map(_.randomNonexistentIndex())
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
