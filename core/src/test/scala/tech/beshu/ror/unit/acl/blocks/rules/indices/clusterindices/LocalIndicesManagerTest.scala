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
package tech.beshu.ror.unit.acl.blocks.rules.indices.clusterindices

import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.LocalIndicesManager
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local as LocalIndexName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullLocalDataStreamWithAliases
import tech.beshu.ror.accesscontrol.domain.IndexAttribute.{Closed, Opened}
import tech.beshu.ror.accesscontrol.domain.IndexAttributeFilter
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.mocks.{MockEsServices, MockRequestContext}
import tech.beshu.ror.syntax.*

class LocalIndicesManagerTest extends AnyWordSpec {

  private val esServices = MockEsServices.`with`(
    MockEsServices.MockEsClusterService(
      allIndicesAndAliases = Set(
        fullLocalIndex("open-1", Opened, "alias-open", "alias-both"),
        fullLocalIndex("closed-1", Closed, "alias-closed", "alias-both")
      ),
      allDataStreamsAndAliases = Set(
        FullLocalDataStreamWithAliases(
          dataStreamName = dataStreamName("logs-app"),
          aliasesNames = Set(dataStreamName("logs-alias")),
          backingIndices = Set(indexName(".ds-logs-app-000001"))
        )
      )
    )
  )

  "LocalIndicesManager" when {
    "index attribute filter is Closed" should {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.Closed)
        .withEsServices(esServices)
      given RequestId = requestContext.id.toRequestId
      val manager = new LocalIndicesManager(requestContext, PatternsMatcher.create(Set(localIndex("*"))))

      "return only aliases of closed indices from allAliases" in {
        manager.allAliases.runSyncUnsafe() shouldBe Set(
          localIndex("alias-closed"),
          localIndex("alias-both")
        )
      }
      "return only the closed index from allIndices" in {
        manager.allIndices.runSyncUnsafe() shouldBe Set(localIndex("closed-1"))
      }
      "return the closed index and its aliases from allIndicesAndAliases" in {
        manager.allIndicesAndAliases.runSyncUnsafe() shouldBe Set(
          localIndex("closed-1"),
          localIndex("alias-closed"),
          localIndex("alias-both")
        )
      }
      "return an alias-to-closed-index map from indicesPerAliasMap" in {
        manager.indicesPerAliasMap.runSyncUnsafe() shouldBe Map(
          localIndex("alias-closed") -> Set(localIndex("closed-1")),
          localIndex("alias-both") -> Set(localIndex("closed-1"))
        )
      }
      "return empty data streams from allDataStreams" in {
        manager.allDataStreams.runSyncUnsafe() shouldBe Set.empty[LocalIndexName]
      }
      "return empty data stream aliases from allDataStreamAliases" in {
        manager.allDataStreamAliases.runSyncUnsafe() shouldBe Set.empty[LocalIndexName]
      }
      "return empty from allDataStreamsAndDataStreamAliases" in {
        manager.allDataStreamsAndDataStreamAliases.runSyncUnsafe() shouldBe Set.empty[LocalIndexName]
      }
      "return empty from dataStreamsPerAliasMap" in {
        manager.dataStreamsPerAliasMap.runSyncUnsafe() shouldBe Map.empty[LocalIndexName, Set[LocalIndexName]]
      }
      "return empty from backingIndicesPerDataStreamMap" in {
        manager.backingIndicesPerDataStreamMap.runSyncUnsafe() shouldBe Map.empty[LocalIndexName, Set[LocalIndexName]]
      }
    }
    "index attribute filter is Opened" should {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.Opened)
        .withEsServices(esServices)
      given RequestId = requestContext.id.toRequestId
      val manager = new LocalIndicesManager(requestContext, PatternsMatcher.create(Set(localIndex("*"))))

      "return only aliases of opened indices from allAliases" in {
        manager.allAliases.runSyncUnsafe() shouldBe Set(
          localIndex("alias-open"),
          localIndex("alias-both")
        )
      }
      "return only the opened index from allIndices" in {
        manager.allIndices.runSyncUnsafe() shouldBe Set(localIndex("open-1"))
      }
      "return the opened index and its aliases from allIndicesAndAliases" in {
        manager.allIndicesAndAliases.runSyncUnsafe() shouldBe Set(
          localIndex("open-1"),
          localIndex("alias-open"),
          localIndex("alias-both")
        )
      }
      "return an alias-to-opened-index map from indicesPerAliasMap" in {
        manager.indicesPerAliasMap.runSyncUnsafe() shouldBe Map(
          localIndex("alias-open") -> Set(localIndex("open-1")),
          localIndex("alias-both") -> Set(localIndex("open-1"))
        )
      }
      "return the data stream from allDataStreams" in {
        manager.allDataStreams.runSyncUnsafe() shouldBe Set(localIndex("logs-app"))
      }
      "return the data stream alias from allDataStreamAliases" in {
        manager.allDataStreamAliases.runSyncUnsafe() shouldBe Set(localIndex("logs-alias"))
      }
      "return data stream, its alias and backing index from allDataStreamsAndDataStreamAliases" in {
        manager.allDataStreamsAndDataStreamAliases.runSyncUnsafe() shouldBe Set(
          localIndex("logs-app"),
          localIndex("logs-alias"),
          localIndex(".ds-logs-app-000001")
        )
      }
      "return alias-to-data-stream map from dataStreamsPerAliasMap" in {
        manager.dataStreamsPerAliasMap.runSyncUnsafe() shouldBe Map(
          localIndex("logs-alias") -> Set(localIndex("logs-app"))
        )
      }
      "return data-stream-to-backing-indices map from backingIndicesPerDataStreamMap" in {
        manager.backingIndicesPerDataStreamMap.runSyncUnsafe() shouldBe Map(
          localIndex("logs-app") -> Set(localIndex(".ds-logs-app-000001"))
        )
      }
    }
    "index attribute filter is All" should {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.All)
        .withEsServices(esServices)
      given RequestId = requestContext.id.toRequestId
      val manager = new LocalIndicesManager(requestContext, PatternsMatcher.create(Set(localIndex("*"))))

      "return all aliases from allAliases" in {
        manager.allAliases.runSyncUnsafe() shouldBe Set(
          localIndex("alias-open"),
          localIndex("alias-closed"),
          localIndex("alias-both")
        )
      }
      "return all indices from allIndices" in {
        manager.allIndices.runSyncUnsafe() shouldBe Set(
          localIndex("open-1"),
          localIndex("closed-1")
        )
      }
      "return all indices and aliases from allIndicesAndAliases" in {
        manager.allIndicesAndAliases.runSyncUnsafe() shouldBe Set(
          localIndex("open-1"),
          localIndex("closed-1"),
          localIndex("alias-open"),
          localIndex("alias-closed"),
          localIndex("alias-both")
        )
      }
      "return a full alias-to-indices map from indicesPerAliasMap" in {
        manager.indicesPerAliasMap.runSyncUnsafe() shouldBe Map(
          localIndex("alias-open") -> Set(localIndex("open-1")),
          localIndex("alias-closed") -> Set(localIndex("closed-1")),
          localIndex("alias-both") -> Set(localIndex("open-1"), localIndex("closed-1"))
        )
      }
      "return the data stream from allDataStreams" in {
        manager.allDataStreams.runSyncUnsafe() shouldBe Set(localIndex("logs-app"))
      }
      "return the data stream alias from allDataStreamAliases" in {
        manager.allDataStreamAliases.runSyncUnsafe() shouldBe Set(localIndex("logs-alias"))
      }
      "return data stream, its alias and backing index from allDataStreamsAndDataStreamAliases" in {
        manager.allDataStreamsAndDataStreamAliases.runSyncUnsafe() shouldBe Set(
          localIndex("logs-app"),
          localIndex("logs-alias"),
          localIndex(".ds-logs-app-000001")
        )
      }
      "return alias-to-data-stream map from dataStreamsPerAliasMap" in {
        manager.dataStreamsPerAliasMap.runSyncUnsafe() shouldBe Map(
          localIndex("logs-alias") -> Set(localIndex("logs-app"))
        )
      }
      "return data-stream-to-backing-indices map from backingIndicesPerDataStreamMap" in {
        manager.backingIndicesPerDataStreamMap.runSyncUnsafe() shouldBe Map(
          localIndex("logs-app") -> Set(localIndex(".ds-logs-app-000001"))
        )
      }
    }
  }

  private def fullLocalIndex(name: String, attribute: IndexAttribute, aliases: String*): FullLocalIndexWithAliases =
    new FullLocalIndexWithAliases(indexName(name), attribute, aliases.map(indexName).toCovariantSet)

  private def localIndex(name: String): LocalIndexName =
    ClusterIndexName.Local(indexName(name))

  private def indexName(name: String): IndexName.Full =
    IndexName.Full.fromString(name).get

  private def dataStreamName(name: String): DataStreamName.Full =
    DataStreamName.Full.fromString(name).get
}
