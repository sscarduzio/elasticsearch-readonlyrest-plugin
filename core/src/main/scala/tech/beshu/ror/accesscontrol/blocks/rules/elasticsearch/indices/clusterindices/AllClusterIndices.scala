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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices

import cats.implicits._
import cats.kernel.Semigroup
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule.ProcessResult
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, KibanaIndexName}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList
import tech.beshu.ror.accesscontrol.show.logs._

trait AllClusterIndices extends BaseIndicesProcessor {
  this: IndicesRule =>

  protected def processIndices(requestContext: RequestContext,
                               allAllowedIndices: Set[ClusterIndexName],
                               requestedIndices: Set[ClusterIndexName],
                               determinedKibanaIndex: Option[KibanaIndexName]): Task[ProcessResult[ClusterIndexName]] = {
    val (allAllowedRemoteIndices, allAllowedLocalIndices) = splitIntoRemoteAndLocalIndices(allAllowedIndices)
    val (requestedRemoteIndices, requestedLocalIndices) = splitIntoRemoteAndLocalIndices(requestedIndices)

    (UniqueNonEmptyList.fromIterable(requestedLocalIndices), UniqueNonEmptyList.fromIterable(requestedRemoteIndices)) match {
      case (Some(nonEmptyRequestedLocalIndices), Some(nonEmptyRequestedRemoteIndices)) =>
        import AllClusterIndices._
        for {
          localResult <- processLocalIndices(requestContext, allAllowedLocalIndices, nonEmptyRequestedLocalIndices, determinedKibanaIndex)
          remoteResult <- processRemoteIndices(requestContext, allAllowedRemoteIndices, nonEmptyRequestedRemoteIndices, determinedKibanaIndex)
        } yield Semigroup.combine(localResult, remoteResult)
      case (Some(nonEmptyRequestedLocalIndices), None) =>
        processLocalIndices(requestContext, allAllowedLocalIndices, nonEmptyRequestedLocalIndices, determinedKibanaIndex)
      case (None, Some(nonEmptyRequestedRemoteIndices)) =>
        processRemoteIndices(requestContext, allAllowedRemoteIndices, nonEmptyRequestedRemoteIndices, determinedKibanaIndex)
      case (None, None) =>
        if (requestContext.allIndicesAndAliases.nonEmpty || requestContext.allDataStreamsAndAliases.nonEmpty) {
          Task.now(ProcessResult.Ok(allAllowedIndices))
        } else {
          Task.now(ProcessResult.Failed(Some(Cause.IndexNotFound)))
        }
    }
  }

  private def processLocalIndices(requestContext: RequestContext,
                                  allAllowedIndices: Set[ClusterIndexName.Local],
                                  requestedIndices: UniqueNonEmptyList[ClusterIndexName.Local],
                                  determinedKibanaIndex: Option[KibanaIndexName]): Task[ProcessResult[ClusterIndexName]] = {
    implicit val indicesManager: LocalIndicesManager = new LocalIndicesManager(
      requestContext,
      PatternsMatcher.create(allAllowedIndices)
    )
    logger.debug(s"[${requestContext.id.show}] Checking local indices (allowed: [${allAllowedIndices.map(_.show).mkString(",")}], requested: [${requestedIndices.map(_.show).mkString(",")}])")
    canPass(requestContext, determinedKibanaIndex, requestedIndices)
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
                                   requestedIndices: UniqueNonEmptyList[ClusterIndexName.Remote],
                                   determinedKibanaIndex: Option[KibanaIndexName]): Task[ProcessResult[ClusterIndexName]] = {
    implicit val indicesManager: RemoteIndicesManager = new RemoteIndicesManager(
      requestContext,
      PatternsMatcher.create(allAllowedIndices)
    )
    logger.debug(s"[${requestContext.id.show}] Checking remote indices (allowed: [${allAllowedIndices.map(_.show).mkString(",")}], requested: [${requestedIndices.map(_.show).mkString(",")}])")
    canPass(requestContext, determinedKibanaIndex, requestedIndices)
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

object AllClusterIndices {
  implicit def processResultSemigroup: Semigroup[ProcessResult[ClusterIndexName]] = Semigroup.instance {
    case (ProcessResult.Ok(set1), ProcessResult.Ok(set2)) => ProcessResult.Ok(set1 ++ set2)
    case (ok@ProcessResult.Ok(_), ProcessResult.Failed(_)) => ok
    case (ProcessResult.Failed(_), ok@ProcessResult.Ok(_)) => ok
    case (failed@ProcessResult.Failed(_), ProcessResult.Failed(_)) => failed
  }
}