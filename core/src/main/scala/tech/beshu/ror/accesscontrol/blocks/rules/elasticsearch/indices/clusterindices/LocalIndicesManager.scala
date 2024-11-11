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
import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local as LocalIndexName
import tech.beshu.ror.accesscontrol.domain.IndexAttribute
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*

class LocalIndicesManager(requestContext: RequestContext,
                          override val allowedIndicesMatcher: PatternsMatcher[LocalIndexName])
  extends IndicesManager[LocalIndexName] {

  override def allIndicesAndAliases: Task[Set[LocalIndexName]] = Task.delay {
    indices(requestContext.indexAttributes).flatMap(_.all)
  }

  override def allIndices: Task[Set[LocalIndexName]] = Task.delay {
    indices(requestContext.indexAttributes).map(_.index)
  }

  override def allAliases: Task[Set[LocalIndexName]] = Task.delay {
    requestContext.allIndicesAndAliases.flatMap(_.aliases)
  }

  override def indicesPerAliasMap: Task[Map[LocalIndexName, Set[LocalIndexName]]] = Task.delay {
    indices(requestContext.indexAttributes)
      .foldLeft(Map.empty[LocalIndexName, Set[LocalIndexName]]) {
        case (acc, indexWithAliases) =>
          val localIndicesPerAliasMap = indexWithAliases.aliases.map((_, Set(indexWithAliases.index))).toMap
          mapMonoid.combine(acc, localIndicesPerAliasMap)
      }
  }

  override def allDataStreamsAndDataStreamAliases: Task[Set[LocalIndexName]] = Task.delay {
    dataStreams(requestContext.indexAttributes).flatMap(_.all)
  }

  override def allDataStreams: Task[Set[LocalIndexName]] = Task.delay {
    dataStreams(requestContext.indexAttributes).map(_.dataStream)
  }

  override def allDataStreamAliases: Task[Set[LocalIndexName]] = Task.delay {
    requestContext
      .allDataStreamsAndAliases
      .flatMap(_.aliases)
  }

  override def dataStreamsPerAliasMap: Task[Map[LocalIndexName, Set[LocalIndexName]]] = Task.delay {
    dataStreams(requestContext.indexAttributes)
      .foldLeft(Map.empty[LocalIndexName, Set[LocalIndexName]]) {
        case (acc, dataStreamWithAliases) =>
          val localDataStreamsPerAliasMap = dataStreamWithAliases.aliases.map((_, Set(dataStreamWithAliases.dataStream))).toMap
          mapMonoid.combine(acc, localDataStreamsPerAliasMap)
      }
  }

  override def backingIndicesPerDataStreamMap: Task[Map[LocalIndexName, Set[LocalIndexName]]] = Task.delay {
    dataStreams(requestContext.indexAttributes)
      .foldLeft(Map.empty[LocalIndexName, Set[LocalIndexName]]) {
        case (acc, fullDataStream) =>
          val backingIndicesPerDataStream = Map(fullDataStream.dataStream -> fullDataStream.indices)
          mapMonoid.combine(acc, backingIndicesPerDataStream)
      }
  }

  private def dataStreams(filteredBy: Set[IndexAttribute]) =
    requestContext
      .allDataStreamsAndAliases
      .filter(ds =>
        if (filteredBy.nonEmpty) filteredBy.contains(ds.attribute)
        else true
      )

  private def indices(filteredBy: Set[IndexAttribute]) = {
    requestContext
      .allIndicesAndAliases
      .filter(i =>
        if (filteredBy.nonEmpty) filteredBy.contains(i.attribute)
        else true
      )
  }

  private lazy val mapMonoid: Monoid[Map[LocalIndexName, Set[LocalIndexName]]] =
    Monoid[Map[LocalIndexName, Set[LocalIndexName]]]

}
