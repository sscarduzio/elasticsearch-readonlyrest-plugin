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
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote
import tech.beshu.ror.accesscontrol.domain.FullRemoteIndexWithAliases
import tech.beshu.ror.accesscontrol.matchers.IndicesMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext

class RemoteIndicesManager(requestContext: RequestContext,
                           override val matcher: IndicesMatcher[Remote])
  extends IndicesManager[Remote] {

  override def allIndicesAndAliases: Task[Set[Remote]] =
    requestContext.allRemoteIndicesAndAliases.map(_.flatMap(_.all))

  override def allIndices: Task[Set[Remote]] =
    requestContext.allRemoteIndicesAndAliases.map(_.map(r => Remote(r.indexName, r.clusterName)))

  override def allAliases: Task[Set[Remote]] =
    requestContext.allRemoteIndicesAndAliases.map(_.flatMap(r => r.aliasesNames.map(Remote(_, r.clusterName))))

  override def indicesPerAliasMap: Task[Map[Remote, Set[Remote]]] = {
    val mapMonoid = Monoid[Map[Remote, Set[Remote]]]
    requestContext
      .allRemoteIndicesAndAliases
        .map {
          _.foldLeft(Map.empty[Remote, Set[Remote]]) {
            case (acc, FullRemoteIndexWithAliases(clusterName, index, aliases)) =>
              val localIndicesPerAliasMap = aliases
                .map(Remote(_, clusterName))
                .map((_, Set(Remote(index, clusterName))))
                .toMap
              mapMonoid.combine(acc, localIndicesPerAliasMap)
          }
        }
  }
}
