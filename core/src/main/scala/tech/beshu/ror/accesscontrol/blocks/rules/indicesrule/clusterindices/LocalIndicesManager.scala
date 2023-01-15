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

import cats.Monoid
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.clusterindices.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local
import tech.beshu.ror.accesscontrol.domain.IndexAttribute
import tech.beshu.ror.accesscontrol.matchers.IndicesMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext

class LocalIndicesManager(requestContext: RequestContext,
                          override val matcher: IndicesMatcher[Local])
  extends IndicesManager[Local] {

  override def allIndicesAndAliases: Task[Set[Local]] = Task.delay {
    indices(requestContext.indexAttributes).flatMap(_.all)
  }

  override def allIndices: Task[Set[Local]] = Task.delay {
    indices(requestContext.indexAttributes).map(_.index)
  }

  override def allAliases: Task[Set[Local]] = Task.delay {
    requestContext.allIndicesAndAliases.flatMap(_.aliases)
  }

  override def indicesPerAliasMap: Task[Map[Local, Set[Local]]] = Task.delay {
    indices(requestContext.indexAttributes)
      .foldLeft(Map.empty[Local, Set[Local]]) {
        case (acc, indexWithAliases) =>
          val localIndicesPerAliasMap = indexWithAliases.aliases.map((_, Set(indexWithAliases.index))).toMap
          mapMonoid.combine(acc, localIndicesPerAliasMap)
      }
  }

  override def allDataStreamsAndDataStreamAliases: Task[Set[Local]] = Task.delay {
    dataStreams(requestContext.indexAttributes).flatMap(_.all)
  }

  override def allDataStreams: Task[Set[Local]] = Task.delay {
    dataStreams(requestContext.indexAttributes).map(_.dataStream)
  }

  override def allDataStreamAliases: Task[Set[Local]] = Task.delay {
    requestContext
      .allDataStreamsAndAliases
      .flatMap(_.aliases)
  }

  override def dataStreamsPerAliasMap: Task[Map[Local, Set[Local]]] = Task.delay {
    dataStreams(requestContext.indexAttributes)
      .foldLeft(Map.empty[Local, Set[Local]]) {
        case (acc, dataStreamWithAliases) =>
          val localDataStreamsPerAliasMap = dataStreamWithAliases.aliases.map((_, Set(dataStreamWithAliases.dataStream))).toMap
          mapMonoid.combine(acc, localDataStreamsPerAliasMap)
      }
  }

  override def indicesPerDataStreamMap: Task[Map[Local, Set[Local]]] = Task.delay {
    dataStreams(requestContext.indexAttributes)
      .foldLeft(Map.empty[Local, Set[Local]]) {
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

  private lazy val mapMonoid: Monoid[Map[Local, Set[Local]]] = Monoid[Map[Local, Set[Local]]]

}
