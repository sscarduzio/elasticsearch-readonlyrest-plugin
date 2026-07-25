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

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote as RemoteIndexName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullRemoteDataStreamWithAliases
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.drainToMap

import scala.collection.mutable

class RemoteIndicesManager(
    requestContext: RequestContext,
    override val allowedIndicesMatcher: PatternsMatcher[RemoteIndexName]
) extends IndicesManager[RemoteIndexName] {

  private implicit val implicitRequestId: RequestId = requestContext.id.toRequestId
  private val clusterService = requestContext.esServices.clusterService
  private val indexAttributesFromRequest = requestContext.indexAttributes

  private lazy val cachedRemoteIndices =
    clusterService.allRemoteIndicesAndAliases.map { all =>
      indexAttributesFromRequest match {
        case IndexAttributeFilter.All    => all
        case IndexAttributeFilter.Opened => all.filter(_.attribute == IndexAttribute.Opened)
        case IndexAttributeFilter.Closed => all.filter(_.attribute == IndexAttribute.Closed)
      }
    }.memoize

  private lazy val cachedAllIndicesAndAliases: Task[Set[RemoteIndexName]] =
    cachedRemoteIndices.map(_.flatMap(_.all)).memoize

  private lazy val cachedAllIndices: Task[Set[RemoteIndexName]] =
    cachedRemoteIndices.map(_.map(_.index)).memoize

  private lazy val cachedAllAliases: Task[Set[RemoteIndexName]] =
    clusterService.allRemoteIndicesAndAliases
      .map(_.flatMap(_.aliases))
      .memoize

  private lazy val cachedIndicesPerAliasMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedRemoteIndices.map(indicesPerAliasMapFrom).memoize

  private lazy val cachedRemoteDataStreams =
    clusterService.allRemoteDataStreamsAndAliases.map { all =>
      indexAttributesFromRequest match {
        case IndexAttributeFilter.Closed => Set.empty[FullRemoteDataStreamWithAliases]
        case IndexAttributeFilter.Opened => all
        case IndexAttributeFilter.All    => all
      }
    }.memoize

  private lazy val cachedAllDataStreamsAndAliases: Task[Set[RemoteIndexName]] =
    cachedRemoteDataStreams.map(_.flatMap(_.all)).memoize

  private lazy val cachedAllDataStreams: Task[Set[RemoteIndexName]] =
    cachedRemoteDataStreams.map(_.map(_.dataStream)).memoize

  private lazy val cachedAllDataStreamAliases: Task[Set[RemoteIndexName]] =
    clusterService.allRemoteDataStreamsAndAliases
      .map(_.flatMap(_.aliases))
      .memoize

  private lazy val cachedDataStreamsPerAliasMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedRemoteDataStreams.map(dataStreamsPerAliasMapFrom).memoize

  private lazy val cachedBackingIndicesPerDataStreamMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedRemoteDataStreams.map(backingIndicesPerDataStreamMapFrom).memoize

  override def allIndicesAndAliases(
      implicit id: RequestId
  ): Task[Set[RemoteIndexName]] =
    cachedAllIndicesAndAliases

  override def allIndices(
      implicit id: RequestId
  ): Task[Set[RemoteIndexName]] =
    cachedAllIndices

  override def allAliases(
      implicit id: RequestId
  ): Task[Set[RemoteIndexName]] =
    cachedAllAliases

  override def indicesPerAliasMap(
      implicit id: RequestId
  ): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedIndicesPerAliasMap

  override def allDataStreamsAndDataStreamAliases(
      implicit id: RequestId
  ): Task[Set[RemoteIndexName]] =
    cachedAllDataStreamsAndAliases

  override def allDataStreams(
      implicit id: RequestId
  ): Task[Set[RemoteIndexName]] =
    cachedAllDataStreams

  override def allDataStreamAliases(
      implicit id: RequestId
  ): Task[Set[RemoteIndexName]] =
    cachedAllDataStreamAliases

  override def dataStreamsPerAliasMap(
      implicit id: RequestId
  ): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedDataStreamsPerAliasMap

  override def backingIndicesPerDataStreamMap(
      implicit id: RequestId
  ): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedBackingIndicesPerDataStreamMap

  private def indicesPerAliasMapFrom(
      indices: Iterable[FullRemoteIndexWithAliases]
  ): Map[RemoteIndexName, Set[RemoteIndexName]] = {
    val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
    indices.foreach { remoteIndexWithAliases =>
      remoteIndexWithAliases.aliases.foreach { alias =>
        collected.getOrElseUpdate(alias, Set.newBuilder[RemoteIndexName]) += remoteIndexWithAliases.index
      }
    }
    collected.drainToMap
  }

  private def dataStreamsPerAliasMapFrom(
      dataStreams: Iterable[FullRemoteDataStreamWithAliases]
  ): Map[RemoteIndexName, Set[RemoteIndexName]] = {
    val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
    dataStreams.foreach { dataStreamWithAliases =>
      dataStreamWithAliases.aliases.foreach { alias =>
        collected.getOrElseUpdate(alias, Set.newBuilder[RemoteIndexName]) += dataStreamWithAliases.dataStream
      }
    }
    collected.drainToMap
  }

  private def backingIndicesPerDataStreamMapFrom(
      dataStreams: Iterable[FullRemoteDataStreamWithAliases]
  ): Map[RemoteIndexName, Set[RemoteIndexName]] = {
    val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
    dataStreams.foreach { fullRemoteDataStream =>
      fullRemoteDataStream.indices.foreach { index =>
        collected.getOrElseUpdate(fullRemoteDataStream.dataStream, Set.newBuilder[RemoteIndexName]) += index
      }
    }
    collected.drainToMap
  }

}
