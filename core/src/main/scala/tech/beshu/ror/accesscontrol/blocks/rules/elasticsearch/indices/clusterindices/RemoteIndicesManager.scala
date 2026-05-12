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

import scala.collection.mutable

class RemoteIndicesManager(requestContext: RequestContext,
                           override val allowedIndicesMatcher: PatternsMatcher[RemoteIndexName])
  extends IndicesManager[RemoteIndexName] {

  private implicit val implicitRequestId: RequestId = requestContext.id.toRequestId
  private val clusterService = requestContext.esServices.clusterService
  private val indexAttributesFromRequest = requestContext.indexAttributes
  private val allIndexAttributes: Set[IndexAttribute] = Set(IndexAttribute.Opened, IndexAttribute.Closed)

  // Indices — memoize filtered results and derived flat sets so work is done at most once per request.
  private lazy val cachedRemoteIndices =
    clusterService.allRemoteIndicesAndAliases
      .map { all =>
        if (indexAttributesFromRequest.isEmpty || indexAttributesFromRequest == allIndexAttributes) all
        else all.filter(i => indexAttributesFromRequest.contains(i.attribute))
      }
      .memoize

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

  // Data streams — same memoization pattern.
  private lazy val cachedRemoteDataStreams =
    clusterService.allRemoteDataStreamsAndAliases
      .map { all =>
        if (indexAttributesFromRequest.isEmpty || indexAttributesFromRequest.contains(IndexAttribute.Opened)) all
        else Set.empty
      }
      .memoize

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

  override def allIndicesAndAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllIndicesAndAliases

  override def allIndices(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllIndices

  override def allAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllAliases

  override def indicesPerAliasMap(implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedIndicesPerAliasMap

  override def allDataStreamsAndDataStreamAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllDataStreamsAndAliases

  override def allDataStreams(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllDataStreams

  override def allDataStreamAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllDataStreamAliases

  override def dataStreamsPerAliasMap(implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedDataStreamsPerAliasMap

  override def backingIndicesPerDataStreamMap(implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cachedBackingIndicesPerDataStreamMap

  private def indicesPerAliasMapFrom(indices: Iterable[FullRemoteIndexWithAliases]): Map[RemoteIndexName, Set[RemoteIndexName]] = {
    val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
    indices.foreach { remoteIndexWithAliases =>
      remoteIndexWithAliases.aliases.foreach { alias =>
        collected.getOrElseUpdate(alias, Set.newBuilder[RemoteIndexName]) += remoteIndexWithAliases.index
      }
    }
    buildAliasMap(collected)
  }

  private def dataStreamsPerAliasMapFrom(dataStreams: Iterable[FullRemoteDataStreamWithAliases]): Map[RemoteIndexName, Set[RemoteIndexName]] = {
    val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
    dataStreams.foreach { dataStreamWithAliases =>
      dataStreamWithAliases.aliases.foreach { alias =>
        collected.getOrElseUpdate(alias, Set.newBuilder[RemoteIndexName]) += dataStreamWithAliases.dataStream
      }
    }
    buildAliasMap(collected)
  }

  private def backingIndicesPerDataStreamMapFrom(dataStreams: Iterable[FullRemoteDataStreamWithAliases]): Map[RemoteIndexName, Set[RemoteIndexName]] = {
    val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
    dataStreams.foreach { fullRemoteDataStream =>
      fullRemoteDataStream.indices.foreach { index =>
        collected.getOrElseUpdate(fullRemoteDataStream.dataStream, Set.newBuilder[RemoteIndexName]) += index
      }
    }
    buildAliasMap(collected)
  }

  private def buildAliasMap[K, V](collected: mutable.HashMap[K, mutable.Builder[V, Set[V]]]): Map[K, Set[V]] = {
    val mapBuilder = Map.newBuilder[K, Set[V]]
    mapBuilder.sizeHint(collected.size)
    collected.foreach { case (k, setBuilder) =>
      mapBuilder += (k -> setBuilder.result())
    }
    mapBuilder.result()
  }
}
