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
package tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.clusterindices

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule.ProcessResult
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.IndicesMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext

trait AllClusterIndices2 extends BaseIndicesProcessor {
  this: IndicesRule =>

  protected def processIndices(requestContext: RequestContext,
                               allAllowedIndices: Set[ClusterIndexName],
                               requestedIndices: Set[ClusterIndexName]): Task[ProcessResult[ClusterIndexName]] = {
    val (allAllowedRemoteIndices, allAllowedLocalIndices) = splitIntoRemoteAndLocalIndices(allAllowedIndices)
    val (requestedRemoteIndices, requestedLocalIndices) = splitIntoRemoteAndLocalIndices(requestedIndices)

    for {
      localIndicesProcessingResult <- processLocalIndices(requestContext, allAllowedLocalIndices, requestedLocalIndices)
      remoteIndicesProcessingResult <- processRemoteIndices(requestContext, allAllowedRemoteIndices, requestedRemoteIndices)
    } yield {
      (localIndicesProcessingResult, remoteIndicesProcessingResult) match {
        case (ProcessResult.Ok(localIndices), ProcessResult.Ok(remoteIndices)) =>
          ProcessResult.Ok(localIndices ++ remoteIndices)
        case (ok@ProcessResult.Ok(_), ProcessResult.Failed(_)) =>
          ok
        case (ProcessResult.Failed(_), ok@ProcessResult.Ok(_)) =>
          ok
        case (ProcessResult.Failed(localIndicesCause), ProcessResult.Failed(remoteIndicesCause)) =>
          ProcessResult.Failed(localIndicesCause.orElse(remoteIndicesCause))
      }
    }
  }

  private def processLocalIndices(requestContext: RequestContext,
                                  allAllowedIndices: Set[ClusterIndexName.Local],
                                  requestedIndices: Set[ClusterIndexName.Local]): Task[ProcessResult[ClusterIndexName.Local]] = {
    implicit val indicesManager: LocalIndicesManager = new LocalIndicesManager(
      requestContext,
      IndicesMatcher.create(allAllowedIndices)
    )
    canPass(requestContext, requestedIndices)
      .map {
        case CanPass.Yes(narrowedIndices) =>
          ProcessResult.Ok(narrowedIndices)
        case CanPass.No(Some(Reason.IndexNotExist)) =>
          ProcessResult.Failed(Some(Cause.IndexNotFound))
        case CanPass.No(_) =>
          ProcessResult.Failed(None)
      }
  }

  private def processRemoteIndices(requestContext: RequestContext,
                                   allAllowedIndices: Set[ClusterIndexName.Remote],
                                   requestedIndices: Set[ClusterIndexName.Remote]): Task[ProcessResult[ClusterIndexName.Remote]] = {
    implicit val indicesManager: RemoteIndicesManager = new RemoteIndicesManager(
      requestContext,
      IndicesMatcher.create(allAllowedIndices)
    )
    canPass(requestContext, requestedIndices)
      .map {
        case CanPass.Yes(narrowedIndices) =>
          ProcessResult.Ok(narrowedIndices)
        case CanPass.No(Some(Reason.IndexNotExist)) =>
          ProcessResult.Failed(Some(Cause.IndexNotFound))
        case CanPass.No(_) =>
          ProcessResult.Failed(None)
      }
  }

  private def splitIntoRemoteAndLocalIndices(indices: Set[ClusterIndexName]) = {
    indices.foldLeft((Set.empty[ClusterIndexName.Remote], Set.empty[ClusterIndexName.Local])) {
      case ((remoteIndicesList, localIndicesList), currentIndex) =>
        currentIndex match {
          case local: ClusterIndexName.Local =>
            (remoteIndicesList, localIndicesList + local)
          case remote: ClusterIndexName.Remote =>
            (remoteIndicesList + remote, localIndicesList)
        }
    }
  }

}