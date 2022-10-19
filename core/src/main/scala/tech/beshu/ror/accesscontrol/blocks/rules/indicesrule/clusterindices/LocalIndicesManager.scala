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

import cats.implicits._
import cats.Monoid
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.clusterindices.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexAttribute}
import tech.beshu.ror.accesscontrol.matchers.IndicesMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext

class LocalIndicesManager(requestContext: RequestContext,
                          override val matcher: IndicesMatcher[ClusterIndexName.Local])
  extends IndicesManager[ClusterIndexName.Local] {

  override def allIndicesAndAliases: Task[Set[ClusterIndexName.Local]] = Task.delay {
    indices(requestContext.indexAttributes).flatMap(_.all)
  }

  override def allIndices: Task[Set[ClusterIndexName.Local]] = Task.delay {
    indices(requestContext.indexAttributes).map(_.index)
  }

  override def allAliases: Task[Set[ClusterIndexName.Local]] = Task.delay {
    requestContext.allIndicesAndAliases.flatMap(_.aliases)
  }

  override def indicesPerAliasMap: Task[Map[ClusterIndexName.Local, Set[ClusterIndexName.Local]]] = Task.delay {
    val mapMonoid = Monoid[Map[ClusterIndexName.Local, Set[ClusterIndexName.Local]]]
    indices(requestContext.indexAttributes)
      .foldLeft(Map.empty[ClusterIndexName.Local, Set[ClusterIndexName.Local]]) {
        case (acc, indexWithAliases) =>
          val localIndicesPerAliasMap = indexWithAliases.aliases.map((_, Set(indexWithAliases.index))).toMap
          mapMonoid.combine(acc, localIndicesPerAliasMap)
      }
  }

  private def indices(filteredBy: Set[IndexAttribute]) = {
    requestContext
      .allIndicesAndAliases
      .filter(i =>
        if(filteredBy.nonEmpty) filteredBy.contains(i.attribute)
        else true
      )
  }
}
