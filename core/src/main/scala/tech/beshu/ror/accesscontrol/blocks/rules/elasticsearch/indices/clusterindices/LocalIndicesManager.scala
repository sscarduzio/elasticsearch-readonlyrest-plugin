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
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local as LocalIndexName
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*

class LocalIndicesManager(requestContext: RequestContext,
                          override val allowedIndicesMatcher: PatternsMatcher[LocalIndexName])
  extends IndicesManager[LocalIndexName] {

  private implicit val implicitRequestId: RequestId = requestContext.id.toRequestId
  private val clusterService = requestContext.esServices.clusterService
  private val indexAttributesFromRequest = requestContext.indexAttributes

  private lazy val indicesSnapshot = clusterService.localIndicesSnapshot

  // Indices — for the common case (no attribute filter) use the pre-computed flat sets from the snapshot;
  // for filtered requests compute from the raw set (which carries attribute information).
  private lazy val attributeFilteredIndices = {
    if (indexAttributesFromRequest.nonEmpty) indicesSnapshot.raw.filter(i => indexAttributesFromRequest.contains(i.attribute))
    else indicesSnapshot.raw
  }
  private lazy val cachedAllIndicesAndAliases: Set[LocalIndexName] = {
    if (indexAttributesFromRequest.isEmpty) indicesSnapshot.indicesAndAliases
    else attributeFilteredIndices.flatMap(_.all)
  }
  private lazy val cachedAllIndices: Set[LocalIndexName] = {
    if (indexAttributesFromRequest.isEmpty) indicesSnapshot.indices
    else attributeFilteredIndices.map(_.index)
  }
  private lazy val cachedAllAliases: Set[LocalIndexName] = indicesSnapshot.aliases

  private lazy val dataStreamsSnapshot = clusterService.localDataStreamsSnapshot

  // Data streams — same pattern as indices above.
  private lazy val attributeFilteredDataStreams = {
    if (indexAttributesFromRequest.nonEmpty) dataStreamsSnapshot.raw.filter(ds => indexAttributesFromRequest.contains(ds.attribute))
    else dataStreamsSnapshot.raw
  }
  private lazy val cachedAllDataStreamsAndAliases: Set[LocalIndexName] = {
    if (indexAttributesFromRequest.isEmpty) dataStreamsSnapshot.dataStreamsAndAliases
    else attributeFilteredDataStreams.flatMap(_.all)
  }
  private lazy val cachedAllDataStreams: Set[LocalIndexName] = {
    if (indexAttributesFromRequest.isEmpty) dataStreamsSnapshot.dataStreams
    else attributeFilteredDataStreams.map(_.dataStream)
  }
  private lazy val cachedAllDataStreamAliases: Set[LocalIndexName] = dataStreamsSnapshot.dataStreamAliases

  override def allIndicesAndAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllIndicesAndAliases)

  override def allIndices(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllIndices)

  override def allAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllAliases)

  override def indicesPerAliasMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    clusterService.indicesPerAliasMap(indexAttributesFromRequest)

  override def allDataStreamsAndDataStreamAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllDataStreamsAndAliases)

  override def allDataStreams(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllDataStreams)

  override def allDataStreamAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllDataStreamAliases)

  override def dataStreamsPerAliasMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    clusterService.dataStreamsPerAliasMap(indexAttributesFromRequest)

  override def backingIndicesPerDataStreamMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    clusterService.backingIndicesPerDataStreamMap(indexAttributesFromRequest)
}
