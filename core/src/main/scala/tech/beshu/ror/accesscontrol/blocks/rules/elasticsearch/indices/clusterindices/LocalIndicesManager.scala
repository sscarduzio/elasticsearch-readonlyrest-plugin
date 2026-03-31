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
import tech.beshu.ror.accesscontrol.domain.{IndexAttribute, RequestId}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*

class LocalIndicesManager(requestContext: RequestContext,
                          override val allowedIndicesMatcher: PatternsMatcher[LocalIndexName])
  extends IndicesManager[LocalIndexName] {

  private val clusterService = requestContext.esServices.clusterService

  override def allIndicesAndAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] = Task.delay {
    indices(requestContext.indexAttributes).flatMap(_.all)
  }

  override def allIndices(implicit requestId: RequestId): Task[Set[LocalIndexName]] = Task.delay {
    indices(requestContext.indexAttributes).map(_.index)
  }

  override def allAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] = Task.delay {
    clusterService.allIndicesAndAliases.flatMap(_.aliases)
  }

  override def indicesPerAliasMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] = {
    clusterService.indicesPerAliasMap(requestContext.indexAttributes)
  }

  override def allDataStreamsAndDataStreamAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] = Task.delay {
    dataStreams(requestContext.indexAttributes).flatMap(_.all)
  }

  override def allDataStreams(implicit requestId: RequestId): Task[Set[LocalIndexName]] = Task.delay {
    dataStreams(requestContext.indexAttributes).map(_.dataStream)
  }

  override def allDataStreamAliases(implicit requestId: RequestId): Task[Set[LocalIndexName]] = Task.delay {
    clusterService
      .allDataStreamsAndAliases
      .flatMap(_.aliases)
  }

  override def dataStreamsPerAliasMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =  {
    clusterService.dataStreamsPerAliasMap(requestContext.indexAttributes)
  }

  override def backingIndicesPerDataStreamMap(implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] = {
    clusterService.backingIndicesPerDataStreamMap(requestContext.indexAttributes)
  }

  private def dataStreams(filteredBy: Set[IndexAttribute])
                         (implicit requestId: RequestId) = {
    clusterService
      .allDataStreamsAndAliases
      .filter(ds =>
        if (filteredBy.nonEmpty) filteredBy.contains(ds.attribute)
        else true
      )
  }

  private def indices(filteredBy: Set[IndexAttribute])
                     (implicit requestId: RequestId) = {
    clusterService
      .allIndicesAndAliases
      .filter(i =>
        if (filteredBy.nonEmpty) filteredBy.contains(i.attribute)
        else true
      )
  }
}
