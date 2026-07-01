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

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.RemoteIndicesManager
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote as RemoteIndexName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullRemoteDataStreamWithAliases
import tech.beshu.ror.accesscontrol.domain.IndexAttribute.{Closed, Opened}
import tech.beshu.ror.accesscontrol.domain.IndexAttributeFilter
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.mocks.{MockEsServices, MockRequestContext}
import tech.beshu.ror.syntax.*

class RemoteIndicesManagerTest extends AnyWordSpec {

  "RemoteIndicesManager" should {
    "reuse cached remote indices when building alias maps" in {
      val clusterService = new CountingRemoteClusterService(
        remoteIndices = Set(fullRemoteIndexWithAliases("es_us", "logs-000001", Opened, "logs-alias"))
      )
      val requestContext = MockRequestContext.indices.withEsServices(MockEsServices.`with`(clusterService))
      given RequestId = requestContext.id.toRequestId
      val manager =
        new RemoteIndicesManager(requestContext, PatternsMatcher.create(Set(remoteIndex("es_us", "logs-000001"))))

      val task = for {
        _ <- manager.allIndicesAndAliases
        _ <- manager.allIndices
        _ <- manager.indicesPerAliasMap
      } yield ()

      task.runSyncUnsafe()

      clusterService.remoteIndicesCalls shouldBe 1
    }
    "reuse cached remote data streams when building derived maps" in {
      val clusterService = new CountingRemoteClusterService(
        remoteDataStreams = Set(fullRemoteDataStreamWithAliases("es_us", "logs-ds", "logs-ds-alias"))
      )
      val requestContext = MockRequestContext.indices.withEsServices(MockEsServices.`with`(clusterService))
      given RequestId = requestContext.id.toRequestId
      val manager =
        new RemoteIndicesManager(requestContext, PatternsMatcher.create(Set(remoteIndex("es_us", "logs-ds"))))

      val task = for {
        _ <- manager.allDataStreamsAndDataStreamAliases
        _ <- manager.allDataStreams
        _ <- manager.dataStreamsPerAliasMap
        _ <- manager.backingIndicesPerDataStreamMap
      } yield ()

      task.runSyncUnsafe()

      clusterService.remoteDataStreamsCalls shouldBe 1
    }
    "not filter aliases by index attribute" in {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.Closed)
        .withEsServices(
          MockEsServices.`with`(
            MockEsServices.MockEsClusterService(
              allRemoteIndicesAndAliases = Set(
                fullRemoteIndexWithAliases("es_us", "open-1", Opened, "alias-open", "alias-both"),
                fullRemoteIndexWithAliases("es_us", "closed-1", Closed, "alias-closed", "alias-both")
              )
            )
          )
        )
      given RequestId = requestContext.id.toRequestId
      val manager = new RemoteIndicesManager(requestContext, PatternsMatcher.create(Set(remoteIndex("es_us", "*"))))

      manager.allAliases.runSyncUnsafe() shouldBe Set(
        remoteIndex("es_us", "alias-open"),
        remoteIndex("es_us", "alias-both"),
        remoteIndex("es_us", "alias-closed")
      )
      manager.allIndices.runSyncUnsafe() shouldBe Set(remoteIndex("es_us", "closed-1"))
    }
    "filter indices by Opened attribute" in {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.Opened)
        .withEsServices(
          MockEsServices.`with`(
            MockEsServices.MockEsClusterService(
              allRemoteIndicesAndAliases = Set(
                fullRemoteIndexWithAliases("es_us", "open-1", Opened, "alias-open"),
                fullRemoteIndexWithAliases("es_us", "closed-1", Closed, "alias-closed")
              )
            )
          )
        )
      given RequestId = requestContext.id.toRequestId
      val manager = new RemoteIndicesManager(requestContext, PatternsMatcher.create(Set(remoteIndex("es_us", "*"))))

      manager.allIndices.runSyncUnsafe() shouldBe Set(remoteIndex("es_us", "open-1"))
      manager.allIndicesAndAliases.runSyncUnsafe() shouldBe Set(
        remoteIndex("es_us", "open-1"),
        remoteIndex("es_us", "alias-open")
      )
      manager.indicesPerAliasMap.runSyncUnsafe() shouldBe Map(
        remoteIndex("es_us", "alias-open") -> Set(remoteIndex("es_us", "open-1"))
      )
    }
    "filter indices by Closed attribute" in {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.Closed)
        .withEsServices(
          MockEsServices.`with`(
            MockEsServices.MockEsClusterService(
              allRemoteIndicesAndAliases = Set(
                fullRemoteIndexWithAliases("es_us", "open-1", Opened, "alias-open"),
                fullRemoteIndexWithAliases("es_us", "closed-1", Closed, "alias-closed")
              )
            )
          )
        )
      given RequestId = requestContext.id.toRequestId
      val manager = new RemoteIndicesManager(requestContext, PatternsMatcher.create(Set(remoteIndex("es_us", "*"))))

      manager.allIndices.runSyncUnsafe() shouldBe Set(remoteIndex("es_us", "closed-1"))
      manager.allIndicesAndAliases.runSyncUnsafe() shouldBe Set(
        remoteIndex("es_us", "closed-1"),
        remoteIndex("es_us", "alias-closed")
      )
      manager.indicesPerAliasMap.runSyncUnsafe() shouldBe Map(
        remoteIndex("es_us", "alias-closed") -> Set(remoteIndex("es_us", "closed-1"))
      )
    }
    "return empty data stream results when index attribute filter is Closed" in {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.Closed)
        .withEsServices(
          MockEsServices.`with`(
            MockEsServices.MockEsClusterService(
              allRemoteDataStreamsAndAliases = Set(
                fullRemoteDataStreamWithAliases("es_us", "logs-ds", "logs-ds-alias")
              )
            )
          )
        )
      given RequestId = requestContext.id.toRequestId
      val manager = new RemoteIndicesManager(requestContext, PatternsMatcher.create(Set(remoteIndex("es_us", "*"))))

      manager.allDataStreams.runSyncUnsafe() shouldBe Set.empty[RemoteIndexName]
      manager.allDataStreamsAndDataStreamAliases.runSyncUnsafe() shouldBe Set.empty[RemoteIndexName]
      manager.dataStreamsPerAliasMap.runSyncUnsafe() shouldBe Map.empty[RemoteIndexName, Set[RemoteIndexName]]
      manager.backingIndicesPerDataStreamMap.runSyncUnsafe() shouldBe Map.empty[RemoteIndexName, Set[RemoteIndexName]]
    }
    "not filter data stream aliases by index attribute" in {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = IndexAttributeFilter.Closed)
        .withEsServices(
          MockEsServices.`with`(
            MockEsServices.MockEsClusterService(
              allRemoteDataStreamsAndAliases = Set(
                fullRemoteDataStreamWithAliases("es_us", "logs-ds", "logs-ds-alias")
              )
            )
          )
        )
      given RequestId = requestContext.id.toRequestId
      val manager = new RemoteIndicesManager(requestContext, PatternsMatcher.create(Set(remoteIndex("es_us", "*"))))

      manager.allDataStreamAliases.runSyncUnsafe() shouldBe Set(remoteIndex("es_us", "logs-ds-alias"))
      manager.allDataStreams.runSyncUnsafe() shouldBe Set.empty[RemoteIndexName]
    }
  }

  private final class CountingRemoteClusterService(
      remoteIndices: Set[FullRemoteIndexWithAliases] = Set.empty,
      remoteDataStreams: Set[FullRemoteDataStreamWithAliases] = Set.empty
  ) extends MockEsServices.MockEsClusterService(
        allRemoteIndicesAndAliases = remoteIndices,
        allRemoteDataStreamsAndAliases = remoteDataStreams
      ) {

    var remoteIndicesCalls = 0
    var remoteDataStreamsCalls = 0

    override def allRemoteIndicesAndAliases(
        implicit id: RequestId
    ): Task[Set[FullRemoteIndexWithAliases]] = {
      remoteIndicesCalls += 1
      super.allRemoteIndicesAndAliases
    }

    override def allRemoteDataStreamsAndAliases(
        implicit id: RequestId
    ): Task[Set[FullRemoteDataStreamWithAliases]] = {
      remoteDataStreamsCalls += 1
      super.allRemoteDataStreamsAndAliases
    }

  }

  private def fullRemoteIndexWithAliases(
      clusterName: String,
      indexName: String,
      attribute: IndexAttribute,
      aliasNames: String*
  ): FullRemoteIndexWithAliases =
    new FullRemoteIndexWithAliases(
      ClusterName.Full.fromString(clusterName).get,
      IndexName.Full.fromString(indexName).get,
      attribute,
      aliasNames.map(alias => IndexName.Full.fromString(alias).get).toCovariantSet
    )

  private def fullRemoteDataStreamWithAliases(
      clusterName: String,
      dataStreamName: String,
      dataStreamAliases: String*
  ): FullRemoteDataStreamWithAliases =
    FullRemoteDataStreamWithAliases(
      ClusterName.Full.fromString(clusterName).get,
      DataStreamName.Full.fromString(dataStreamName).get,
      aliasesNames = dataStreamAliases.map(alias => DataStreamName.Full.fromString(alias).get).toCovariantSet,
      backingIndices = Set(IndexName.Full.fromString(s".ds-$dataStreamName").get)
    )

  private def remoteIndex(clusterName: String, indexName: String): RemoteIndexName =
    ClusterIndexName.Remote(IndexName.Full.fromString(indexName).get, ClusterName.Full.fromString(clusterName).get)
}
