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

import cats.Monoid
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.{Remote => RemoteIndexName}
import tech.beshu.ror.accesscontrol.domain.{FullRemoteIndexWithAliases, IndexAttribute}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext

class RemoteIndicesManager(requestContext: RequestContext,
                           override val matcher: PatternsMatcher[RemoteIndexName])
  extends IndicesManager[RemoteIndexName] {

  override def allIndicesAndAliases: Task[Set[RemoteIndexName]] =
    remoteIndices(requestContext.indexAttributes).map(_.flatMap(_.all))

  override def allIndices: Task[Set[RemoteIndexName]] =
    remoteIndices(requestContext.indexAttributes).map(_.map(r => RemoteIndexName(r.indexName, r.clusterName)))

  override def allAliases: Task[Set[RemoteIndexName]] =
    requestContext.allRemoteIndicesAndAliases.map(_.flatMap(r => r.aliasesNames.map(RemoteIndexName(_, r.clusterName))))

  override def indicesPerAliasMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] = {
    remoteIndices(requestContext.indexAttributes)
      .map {
        _.foldLeft(Map.empty[RemoteIndexName, Set[RemoteIndexName]]) {
          case (acc, FullRemoteIndexWithAliases(clusterName, index, _, aliases)) =>
            val remoteIndicesPerAliasMap = aliases
              .map(RemoteIndexName(_, clusterName))
              .map((_, Set(RemoteIndexName(index, clusterName))))
              .toMap
            mapMonoid.combine(acc, remoteIndicesPerAliasMap)
        }
      }
  }

  override def allDataStreamsAndDataStreamAliases: Task[Set[RemoteIndexName]] = {
    remoteDataStreams(requestContext.indexAttributes).map(_.flatMap(_.all))
  }

  override def allDataStreams: Task[Set[RemoteIndexName]] = {
    remoteDataStreams(requestContext.indexAttributes).map(_.map(_.dataStream))
  }


  override def allDataStreamAliases: Task[Set[RemoteIndexName]] = {
    requestContext
      .allRemoteIndicesAndAliases.map(_.flatMap(_.aliases))
  }

  override def dataStreamsPerAliasMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] = {
    remoteDataStreams(requestContext.indexAttributes)
      .map {
        _.foldLeft(Map.empty[RemoteIndexName, Set[RemoteIndexName]]) {
          case (acc, fullRemoteDataStream) =>
            val aliasesPerDataStream = Map(fullRemoteDataStream.dataStream -> fullRemoteDataStream.aliases)
            mapMonoid.combine(acc, aliasesPerDataStream)
        }
      }
  }

  override def indicesPerDataStreamMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] = {
    remoteDataStreams(requestContext.indexAttributes)
      .map {
        _.foldLeft(Map.empty[RemoteIndexName, Set[RemoteIndexName]]) {
          case (acc, fullRemoteDataStream) =>
            val backingIndicesPerDataStream = Map(fullRemoteDataStream.dataStream -> fullRemoteDataStream.aliases)
            mapMonoid.combine(acc, backingIndicesPerDataStream)
        }
      }
  }

  private def remoteIndices(filteredBy: Set[IndexAttribute]) = {
    requestContext
      .allRemoteIndicesAndAliases
      .map(_.filter(i =>
        if (filteredBy.nonEmpty) filteredBy.contains(i.attribute)
        else true
      ))
  }

  private def remoteDataStreams(filteredBy: Set[IndexAttribute]) = {
    requestContext
      .allRemoteDataStreamsAndAliases
      .map(_.filter(ds =>
        if (filteredBy.nonEmpty) filteredBy.contains(ds.attribute)
        else true
      ))
  }

  private lazy val mapMonoid: Monoid[Map[RemoteIndexName, Set[RemoteIndexName]]] = Monoid[Map[RemoteIndexName, Set[RemoteIndexName]]]
}
