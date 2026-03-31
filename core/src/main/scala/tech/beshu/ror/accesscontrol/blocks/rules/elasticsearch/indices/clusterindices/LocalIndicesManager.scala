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

  private lazy val filteredIndices = {
    val attrs = requestContext.indexAttributes
    val all = clusterService.allIndicesAndAliases
    if (attrs.nonEmpty) all.filter(i => attrs.contains(i.attribute)) else all
  }
  private lazy val cachedAllIndicesAndAliases: Set[LocalIndexName] = filteredIndices.flatMap(_.all)
  private lazy val cachedAllIndices: Set[LocalIndexName] = filteredIndices.map(_.index)
  private lazy val cachedAllAliases: Set[LocalIndexName] = clusterService.allIndicesAndAliases.flatMap(_.aliases)

  private lazy val filteredDataStreams = {
    val attrs = requestContext.indexAttributes
    val all = clusterService.allDataStreamsAndAliases
    if (attrs.nonEmpty) all.filter(ds => attrs.contains(ds.attribute)) else all
  }
  private lazy val cachedAllDataStreamsAndAliases: Set[LocalIndexName] = filteredDataStreams.flatMap(_.all)
  private lazy val cachedAllDataStreams: Set[LocalIndexName] = filteredDataStreams.map(_.dataStream)
  private lazy val cachedAllDataStreamAliases: Set[LocalIndexName] = clusterService.allDataStreamsAndAliases.flatMap(_.aliases)

  override def allIndicesAndAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllIndicesAndAliases)

  override def allIndices(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllIndices)

  override def allAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllAliases)

  override def indicesPerAliasMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    clusterService.indicesPerAliasMap(requestContext.indexAttributes)

  override def allDataStreamsAndDataStreamAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllDataStreamsAndAliases)

  override def allDataStreams(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllDataStreams)

  override def allDataStreamAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] =
    Task.delay(cachedAllDataStreamAliases)

  override def dataStreamsPerAliasMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    clusterService.dataStreamsPerAliasMap(requestContext.indexAttributes)

  override def backingIndicesPerDataStreamMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    clusterService.backingIndicesPerDataStreamMap(requestContext.indexAttributes)
}
