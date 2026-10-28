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
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote as RemoteIndexName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullRemoteDataStreamWithAliases
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.services.EsClusterService.RemoteClusterLookupError
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.drainToMap

import scala.collection.mutable

class RemoteIndicesManager private (
    requestContext: RequestContext,
    override val allowedIndicesMatcher: PatternsMatcher[RemoteIndexName],
    contents: List[RemoteClusterContent]
) extends IndicesManager[RemoteIndexName] {

  private val indexAttributesFromRequest = requestContext.indexAttributes

  private lazy val allRemoteIndices: Set[FullRemoteIndexWithAliases] = contents.flatMap(_.indices).toCovariantSet

  private lazy val remoteIndices: Set[FullRemoteIndexWithAliases] =
    indexAttributesFromRequest match {
      case IndexAttributeFilter.All    => allRemoteIndices
      case IndexAttributeFilter.Opened => allRemoteIndices.filter(_.attribute == IndexAttribute.Opened)
      case IndexAttributeFilter.Closed => allRemoteIndices.filter(_.attribute == IndexAttribute.Closed)
    }

  private lazy val cachedAllIndicesAndAliases: Task[Set[RemoteIndexName]] =
    Task.eval(remoteIndices.flatMap(_.all)).memoize

  private lazy val cachedAllIndices: Task[Set[RemoteIndexName]] =
    Task.eval(remoteIndices.map(_.index)).memoize

  private lazy val cachedAllAliases: Task[Set[RemoteIndexName]] =
    Task.eval(allRemoteIndices.flatMap(_.aliases)).memoize

  private lazy val cachedIndicesPerAliasMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    Task.eval(indicesPerAliasMapFrom(remoteIndices)).memoize

  private lazy val allRemoteDataStreams: Set[FullRemoteDataStreamWithAliases] =
    contents.flatMap(_.dataStreams).toCovariantSet

  private lazy val remoteDataStreams: Set[FullRemoteDataStreamWithAliases] =
    indexAttributesFromRequest match {
      case IndexAttributeFilter.Closed => Set.empty[FullRemoteDataStreamWithAliases]
      case IndexAttributeFilter.Opened => allRemoteDataStreams
      case IndexAttributeFilter.All    => allRemoteDataStreams
    }

  private lazy val cachedAllDataStreamsAndAliases: Task[Set[RemoteIndexName]] =
    Task.eval(remoteDataStreams.flatMap(_.all)).memoize

  private lazy val cachedAllDataStreams: Task[Set[RemoteIndexName]] =
    Task.eval(remoteDataStreams.map(_.dataStream)).memoize

  private lazy val cachedAllDataStreamAliases: Task[Set[RemoteIndexName]] =
    Task.eval(allRemoteDataStreams.flatMap(_.aliases)).memoize

  private lazy val cachedDataStreamsPerAliasMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    Task.eval(dataStreamsPerAliasMapFrom(remoteDataStreams)).memoize

  private lazy val cachedBackingIndicesPerDataStreamMap: Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    Task.eval(backingIndicesPerDataStreamMapFrom(remoteDataStreams)).memoize

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

object RemoteIndicesManager {

  // ES fails a search that names an unavailable cluster with skip_unavailable: false. Thus one failed cluster gives no
  // manager, and the caller decides what the search gets.
  def create(
      requestContext: RequestContext,
      allowedIndicesMatcher: PatternsMatcher[RemoteIndexName],
      clusters: Set[ClusterName.Full]
  ): Task[Either[RemoteClusterLookupError, RemoteIndicesManager]] = {
    given RequestId = requestContext.id.toRequestId
    val clusterService = requestContext.esServices.clusterService
    Task
      .parTraverse(clusters.toList)(cluster => clusterService.remoteClusterContent(cluster))
      .map { results =>
        results
          .collectFirst { case Left(error) => error }
          .toLeft(
            new RemoteIndicesManager(requestContext, allowedIndicesMatcher, results.collect { case Right(c) => c })
          )
      }
  }

}
