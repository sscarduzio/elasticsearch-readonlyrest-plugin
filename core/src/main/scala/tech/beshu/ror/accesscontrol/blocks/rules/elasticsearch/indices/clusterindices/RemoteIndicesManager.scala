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
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote as RemoteIndexName
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*

class RemoteIndicesManager(requestContext: RequestContext,
                           override val allowedIndicesMatcher: PatternsMatcher[RemoteIndexName])
  extends IndicesManager[RemoteIndexName] {

  private implicit val implicitRequestId: RequestId = requestContext.id.toRequestId
  private val clusterService = requestContext.esServices.clusterService
  private val indexAttributesFromRequest = requestContext.indexAttributes

  // Indices — memoize filtered results and derived flat sets so work is done at most once per request.
  private lazy val cachedRemoteIndices =
    clusterService.allRemoteIndicesAndAliases
      .map { all =>
        if (indexAttributesFromRequest.nonEmpty) all.filter(i => indexAttributesFromRequest.contains(i.attribute))
        else all
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

  // Data streams — same memoization pattern.
  private lazy val cachedRemoteDataStreams =
    clusterService.allRemoteDataStreamsAndAliases
      .map { all =>
        if (indexAttributesFromRequest.nonEmpty) all.filter(ds => indexAttributesFromRequest.contains(ds.attribute))
        else all
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

  override def allIndicesAndAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllIndicesAndAliases

  override def allIndices(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllIndices

  override def allAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllAliases

  override def indicesPerAliasMap(implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    clusterService.remoteIndicesPerAliasMap(indexAttributesFromRequest)

  override def allDataStreamsAndDataStreamAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllDataStreamsAndAliases

  override def allDataStreams(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllDataStreams

  override def allDataStreamAliases(implicit id: RequestId): Task[Set[RemoteIndexName]] =
    cachedAllDataStreamAliases

  override def dataStreamsPerAliasMap(implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    clusterService.remoteDataStreamsPerAliasMap(indexAttributesFromRequest)

  override def backingIndicesPerDataStreamMap(implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    clusterService.remoteBackingIndicesPerDataStreamMap(indexAttributesFromRequest)
}
