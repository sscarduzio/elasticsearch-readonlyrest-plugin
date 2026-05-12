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
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.BaseIndicesProcessor
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason.IndexNotExist
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local as LocalIndexName
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.set.CovariantSet
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class BaseIndicesProcessorTest extends AnyWordSpec {

  "BaseIndicesProcessor" should {
    "skip alias maps for direct index requests" in {
      val requestedIndex = RequestedIndex(localIndex("logs-000001"), excluded = false)
      val manager = new CountingIndicesManager(
        allIndicesAndAliasesValue = Set(localIndex("logs-000001"), localIndex("logs-alias")).toCovariantSet,
        allIndicesValue = Set(localIndex("logs-000001")).toCovariantSet,
        allAliasesValue = Set(localIndex("logs-alias")).toCovariantSet,
        allDataStreamsAndDataStreamAliasesValue = Set(localIndex("logs-ds"), localIndex("logs-ds-alias")).toCovariantSet,
        allDataStreamsValue = Set(localIndex("logs-ds")).toCovariantSet,
        allDataStreamAliasesValue = Set(localIndex("logs-ds-alias")).toCovariantSet,
        allowedIndicesMatcher = PatternsMatcher.create(Set(localIndex("other-index")))
      )

      val result = TestProcessor.run(MockRequestContext.indices, UniqueNonEmptyList.of(requestedIndex))(using manager).runSyncUnsafe()

      result shouldBe CanPass.No(Some(IndexNotExist))
      manager.indicesPerAliasMapCalls shouldBe 0
      manager.dataStreamsPerAliasMapCalls shouldBe 0
      manager.backingIndicesPerDataStreamMapCalls shouldBe 1
    }

    "keep alias-to-index resolution when aliases are actually requested" in {
      val requestedAlias = RequestedIndex(localIndex("logs-alias"), excluded = false)
      val resolvedIndex = localIndex("logs-000001")
      val manager = new CountingIndicesManager(
        allIndicesAndAliasesValue = Set(resolvedIndex, localIndex("logs-alias")).toCovariantSet,
        allIndicesValue = Set(resolvedIndex).toCovariantSet,
        allAliasesValue = Set(localIndex("logs-alias")).toCovariantSet,
        allDataStreamsAndDataStreamAliasesValue = Set(localIndex("logs-ds"), localIndex("logs-ds-alias")).toCovariantSet,
        allDataStreamsValue = Set(localIndex("logs-ds")).toCovariantSet,
        allDataStreamAliasesValue = Set(localIndex("logs-ds-alias")).toCovariantSet,
        indicesPerAliasMapValue = Map(localIndex("logs-alias") -> Set(resolvedIndex).toCovariantSet),
        allowedIndicesMatcher = PatternsMatcher.create(Set(resolvedIndex))
      )

      val result = TestProcessor.run(MockRequestContext.indices, UniqueNonEmptyList.of(requestedAlias))(using manager).runSyncUnsafe()

      result shouldBe CanPass.Yes(Set(RequestedIndex(resolvedIndex, excluded = false)))
      manager.indicesPerAliasMapCalls shouldBe 1
      manager.dataStreamsPerAliasMapCalls shouldBe 0
      manager.backingIndicesPerDataStreamMapCalls shouldBe 0
    }

    "skip backing index maps when requested data streams are allowed by name" in {
      val requestedDataStream = RequestedIndex(localIndex("logs-ds"), excluded = false)
      val manager = new CountingIndicesManager(
        allIndicesAndAliasesValue = Set(localIndex("logs-000001"), localIndex("logs-alias")).toCovariantSet,
        allIndicesValue = Set(localIndex("logs-000001")).toCovariantSet,
        allAliasesValue = Set(localIndex("logs-alias")).toCovariantSet,
        allDataStreamsAndDataStreamAliasesValue = Set(localIndex("logs-ds"), localIndex("logs-ds-alias")).toCovariantSet,
        allDataStreamsValue = Set(localIndex("logs-ds")).toCovariantSet,
        allDataStreamAliasesValue = Set(localIndex("logs-ds-alias")).toCovariantSet,
        allowedIndicesMatcher = PatternsMatcher.create(Set(localIndex("logs-ds")))
      )

      val result = TestProcessor.run(MockRequestContext.indices, UniqueNonEmptyList.of(requestedDataStream))(using manager).runSyncUnsafe()

      result shouldBe CanPass.Yes(Set(requestedDataStream))
      manager.indicesPerAliasMapCalls shouldBe 0
      manager.dataStreamsPerAliasMapCalls shouldBe 0
      manager.backingIndicesPerDataStreamMapCalls shouldBe 0
    }
  }

  private object TestProcessor extends BaseIndicesProcessor with RequestIdAwareLogging {
    def run(requestContext: RequestContext,
            requestedIndices: UniqueNonEmptyList[RequestedIndex[LocalIndexName]])
           (using allowedIndicesManager: IndicesManager[LocalIndexName]) =
      canPass[LocalIndexName](requestContext, None, requestedIndices)
  }

  private final class CountingIndicesManager(allIndicesAndAliasesValue: CovariantSet[LocalIndexName],
                                             allIndicesValue: CovariantSet[LocalIndexName],
                                             allAliasesValue: CovariantSet[LocalIndexName],
                                             allDataStreamsAndDataStreamAliasesValue: CovariantSet[LocalIndexName],
                                             allDataStreamsValue: CovariantSet[LocalIndexName],
                                             allDataStreamAliasesValue: CovariantSet[LocalIndexName],
                                             indicesPerAliasMapValue: Map[LocalIndexName, CovariantSet[LocalIndexName]] = Map.empty,
                                             dataStreamsPerAliasMapValue: Map[LocalIndexName, CovariantSet[LocalIndexName]] = Map.empty,
                                             backingIndicesPerDataStreamMapValue: Map[LocalIndexName, CovariantSet[LocalIndexName]] = Map.empty,
                                             override val allowedIndicesMatcher: PatternsMatcher[LocalIndexName])
    extends IndicesManager[LocalIndexName] {

    var indicesPerAliasMapCalls = 0
    var dataStreamsPerAliasMapCalls = 0
    var backingIndicesPerDataStreamMapCalls = 0

    override def allIndicesAndAliases(implicit id: RequestId): Task[CovariantSet[LocalIndexName]] = Task.now(allIndicesAndAliasesValue)

    override def allIndices(implicit id: RequestId): Task[CovariantSet[LocalIndexName]] = Task.now(allIndicesValue)

    override def allAliases(implicit id: RequestId): Task[CovariantSet[LocalIndexName]] = Task.now(allAliasesValue)

    override def indicesPerAliasMap(implicit id: RequestId): Task[Map[LocalIndexName, CovariantSet[LocalIndexName]]] = Task.delay {
      indicesPerAliasMapCalls += 1
      indicesPerAliasMapValue
    }

    override def allDataStreamsAndDataStreamAliases(implicit id: RequestId): Task[CovariantSet[LocalIndexName]] =
      Task.now(allDataStreamsAndDataStreamAliasesValue)

    override def allDataStreams(implicit id: RequestId): Task[CovariantSet[LocalIndexName]] = Task.now(allDataStreamsValue)

    override def allDataStreamAliases(implicit id: RequestId): Task[CovariantSet[LocalIndexName]] = Task.now(allDataStreamAliasesValue)

    override def dataStreamsPerAliasMap(implicit id: RequestId): Task[Map[LocalIndexName, CovariantSet[LocalIndexName]]] = Task.delay {
      dataStreamsPerAliasMapCalls += 1
      dataStreamsPerAliasMapValue
    }

    override def backingIndicesPerDataStreamMap(implicit id: RequestId): Task[Map[LocalIndexName, CovariantSet[LocalIndexName]]] = Task.delay {
      backingIndicesPerDataStreamMapCalls += 1
      backingIndicesPerDataStreamMapValue
    }
  }

  private def localIndex(name: String): LocalIndexName =
    ClusterIndexName.Local(IndexName.Full.fromString(name).get)
}
