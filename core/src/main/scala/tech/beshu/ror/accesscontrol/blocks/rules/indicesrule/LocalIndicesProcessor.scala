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
package tech.beshu.ror.accesscontrol.blocks.rules.indicesrule

import cats.Monoid
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause.IndexNotFound
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule.ProcessResult
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.LocalIndicesProcessor.LocalIndicesManager
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeRemoteIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.matchers.{IndicesMatcher, ZeroKnowledgeRemoteIndexFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext

trait LocalIndicesProcessor extends BaseIndicesProcessor {
  this: IndicesRule =>

  private val zKindexFilter = new ZeroKnowledgeRemoteIndexFilterScalaAdapter()

  protected def processIndices(requestContext: RequestContext,
                               resolvedAllowedIndices: Set[ClusterIndexName],
                               indices: Set[ClusterIndexName]): Task[ProcessResult[ClusterIndexName]] = {
    val (crossClusterIndices, localIndices) = splitIntoRemoteAndLocalIndices(indices)

    // Scatter gather for local and remote indices barring algorithms
    if (crossClusterIndices.nonEmpty && requestContext.hasRemoteClusters) {
      // Run the local algorithm
      val processedLocalIndicesTask =
        if (localIndices.isEmpty && crossClusterIndices.nonEmpty) {
          // Don't run locally if only have crossCluster, otherwise you'll resolve the equivalent of "*"
          Task.now(localIndices)
        } else {
          val (_, localResolvedAllowedIndices) = splitIntoRemoteAndLocalIndices(resolvedAllowedIndices)
          implicit val localIndicesManager: LocalIndicesManager = new LocalIndicesManager(
            requestContext,
            IndicesMatcher.create(localResolvedAllowedIndices)
          )
          canPass(requestContext, localIndices)
            .map {
              case CanPass.No(Some(Reason.IndexNotExist)) =>
                Set.empty[ClusterIndexName.Local]
              case CanPass.No(_) =>
                Set.empty[ClusterIndexName.Local]
              case CanPass.Yes(narrowedIndices) =>
                narrowedIndices
            }
        }

      processedLocalIndicesTask
        .map { processedLocalIndices =>
          // Run the remote algorithm (without knowing the remote list of indices)
          val (remoteResolvedAllowedIndices, _) = splitIntoRemoteAndLocalIndices(resolvedAllowedIndices)
          val remoteIndicesMatcher = IndicesMatcher.create(remoteResolvedAllowedIndices)
          val allProcessedIndices = zKindexFilter.check(crossClusterIndices, remoteIndicesMatcher.availableIndicesMatcher) match {
            case CheckResult.Ok(processedIndices) => processedIndices ++ processedLocalIndices
            case CheckResult.Failed => processedLocalIndices
          }

          if (allProcessedIndices.nonEmpty) ProcessResult.Ok(allProcessedIndices)
          else ProcessResult.Failed(Some(IndexNotFound))
        }
    } else {
      val (_, localResolvedAllowedIndices) = splitIntoRemoteAndLocalIndices(resolvedAllowedIndices)
      implicit val localIndicesManager: LocalIndicesManager = new LocalIndicesManager(
        requestContext,
        IndicesMatcher.create(localResolvedAllowedIndices)
      )
      canPass(requestContext, localIndices)
      .map {
        case CanPass.Yes(narrowedIndices) =>
          ProcessResult.Ok(narrowedIndices)
        case CanPass.No(Some(Reason.IndexNotExist)) =>
          ProcessResult.Failed(Some(Cause.IndexNotFound))
        case CanPass.No(_) =>
          ProcessResult.Failed(None)
      }
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

object LocalIndicesProcessor {

  class LocalIndicesManager(requestContext: RequestContext,
                            override val matcher: IndicesMatcher[ClusterIndexName.Local])
    extends IndicesManager[ClusterIndexName.Local] {

    override def allIndicesAndAliases: Task[Set[ClusterIndexName.Local]] = Task.delay {
      requestContext.allIndicesAndAliases.flatMap(_.all)
    }

    override def allIndices: Task[Set[ClusterIndexName.Local]] = Task.delay {
      requestContext.allIndicesAndAliases.map(_.index)
    }

    override def allAliases: Task[Set[ClusterIndexName.Local]] = Task.delay {
      requestContext.allIndicesAndAliases.flatMap(_.aliases)
    }

    override def indicesPerAliasMap: Task[Map[ClusterIndexName.Local, Set[ClusterIndexName.Local]]] = Task.delay {
      val mapMonoid = Monoid[Map[ClusterIndexName.Local, Set[ClusterIndexName.Local]]]
      requestContext
        .allIndicesAndAliases
        .foldLeft(Map.empty[ClusterIndexName.Local, Set[ClusterIndexName.Local]]) {
          case (acc, indexWithAliases) =>
            val localIndicesPerAliasMap = indexWithAliases.aliases.map((_, Set(indexWithAliases.index))).toMap
            mapMonoid.combine(acc, localIndicesPerAliasMap)
        }
    }

  }
}