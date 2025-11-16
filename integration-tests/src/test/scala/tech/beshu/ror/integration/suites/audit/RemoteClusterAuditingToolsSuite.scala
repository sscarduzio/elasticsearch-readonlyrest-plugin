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
package tech.beshu.ror.integration.suites.audit

import cats.data.NonEmptyList
import org.scalatest.time.{Millis, Seconds, Span}
import tech.beshu.ror.integration.suites.base.BaseAuditingToolsSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.EsClusterSettings.positiveInt
import tech.beshu.ror.utils.containers.SecurityType.NoSecurityCluster
import tech.beshu.ror.utils.containers.dependencies.*
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.OsUtils.{CurrentOs, ignoreOnWindows}
import tech.beshu.ror.utils.misc.{OsUtils, Version}

import java.util.UUID

class RemoteClusterAuditingToolsSuite
  extends BaseAuditingToolsSuite
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport {

  private val isDataStreamSupported = Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)

  override implicit val rorConfigFileName: String = {
    if (isDataStreamSupported) {
      "/ror_audit/cluster_auditing_tools/readonlyrest.yml"
    } else {
      "/ror_audit/cluster_auditing_tools/readonlyrest_audit_index.yml"
    }
  }

  private lazy val auditEsContainers: List[EsContainer] = {
    val cluster = createLocalClusterContainer(
      EsClusterSettings.create(
        clusterName = "AUDIT",
        securityType = NoSecurityCluster,
        numberOfInstances = positiveInt(3),
      )
    )
    cluster.start()
    cluster.nodes
  }

  private lazy val proxiedContainers = {
    OsUtils.currentOs match {
      // Windows does not support the toxiproxy container
      case CurrentOs.Windows =>
        List.empty
      case CurrentOs.OtherThanWindows =>
        auditEsContainers.map(esContainer => new ToxiproxyContainer(esContainer, 9200))
    }
  }

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override def clusterDependencies: List[DependencyDef] = {
    OsUtils.currentOs match {
      case CurrentOs.Windows =>
        // Windows does not support the toxiproxy container
        auditEsContainers.zipWithIndex.map { case (auditEsContainer, index) =>
          val name = s"AUDIT_$index"
          es(name, auditEsContainer)
        }
      case CurrentOs.OtherThanWindows =>
        proxiedContainers.zipWithIndex.map { case (proxiedContainer, index) =>
          val name = s"AUDIT_$index"
          toxiproxy(name, proxiedContainer)
        }
    }
  }

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(120, Seconds)), interval = scaled(Span(100, Millis)))

  override lazy val destNodesClientProviders: NonEmptyList[ClientProvider] = NonEmptyList.fromListUnsafe(auditEsContainers)

  override protected def baseRorConfig: String = resolvedRorConfigFile.contentAsString

  override protected def baseAuditDataStreamName: Option[String] = Option.when(isDataStreamSupported)("audit_data_stream")

  // Adding the ES cluster fields is enabled in the /cluster_auditing_tools/readonlyrest.yml config file (`DefaultAuditLogSerializerV2` is used)
  override def assertForEveryAuditEntry(entry: JSON): Unit = {
    entry("es_node_name").str shouldBe "ROR_SINGLE_1"
    entry("es_cluster_name").str shouldBe "ROR_SINGLE"
  }

  // This test suite does not execute on Windows: there is currently no Windows version of ToxiproxyContainer
  ignoreOnWindows {
    "Should report audit events in round-robin mode, even when some nodes are unreachable" in {
      rorApiManager.updateRorInIndexConfig(baseRorConfig).forceOKStatusOrConfigAlreadyLoaded()
      val auditNode1 = proxiedContainers(0)
      val auditNode2 = proxiedContainers(1)
      val auditNode3 = proxiedContainers(2)

      def auditEntriesShouldContainEntriesWithGivenTraceIds(traceIds: List[String]): Unit = {
        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            traceIds.foreach { traceId =>
              val entry = findAuditEntryWithTraceId(auditEntries, traceId)
              assertForEveryAuditEntry(entry)
            }
          }
        }
      }

      val traceIds1 = queryTweeterIndexWithRandomTraceId(times = 1)
      auditEntriesShouldContainEntriesWithGivenTraceIds(traceIds1)

      auditNode1.disableNetwork()

      val traceIds2 = queryTweeterIndexWithRandomTraceId(times = 2)
      auditEntriesShouldContainEntriesWithGivenTraceIds(traceIds2)

      auditNode2.disableNetwork()

      val traceIds3 = queryTweeterIndexWithRandomTraceId(times = 3)
      auditEntriesShouldContainEntriesWithGivenTraceIds(traceIds3)

      auditNode3.disableNetwork()

      // all nodes disabled
      Thread.sleep(1000)

      val traceIds4 = queryTweeterIndexWithRandomTraceId(times = 4)

      Thread.sleep(10000)

      // events sent when all nodes are out will be lost
      forEachAuditManager { adminAuditManager =>
        eventually {
          val auditEntries = adminAuditManager.getEntries.force().jsons
          val expectedEntriesCount = List.concat(traceIds1, traceIds2, traceIds2).size
          auditEntries.size shouldEqual expectedEntriesCount

          traceIds4.foreach { q =>
            checkNoEntriesWithTraceId(auditEntries, q)
          }
        }
      }

      auditNode1.enableNetwork()

      val traceIds5 = queryTweeterIndexWithRandomTraceId(times = 5)

      val allExpectedTraceIds = List.concat(traceIds1, traceIds2, traceIds2, traceIds5)
      forEachAuditManager { adminAuditManager =>
        eventually {
          val auditEntries = adminAuditManager.getEntries.force().jsons
          auditEntries.size shouldEqual allExpectedTraceIds.size
        }
      }
    }
  }

  private def queryTweeterIndexWithRandomTraceId(times: Int): List[String] = {
    (1 to times).map { _ =>
        val traceId = UUID.randomUUID().toString
        val indexManager = new IndexManager(
          basicAuthClient("username", "dev"),
          esVersionUsed,
          // header names are left in audit entry - used as 'test' correlation id
          additionalHeaders = Map(traceIdHeaderName(traceId) -> "any")
        )
        val response = indexManager.getIndex("twitter")
        response should have statusCode 200
        traceId
      }
      .toList
  }


  private def findAuditEntryWithTraceId(auditEntries: Iterable[ujson.Value], traceId: String) = {
    val foundEntries = findAuditEntriesWithTraceId(auditEntries, traceId)
    foundEntries.size shouldBe 1
    foundEntries.head
  }

  private def checkNoEntriesWithTraceId(auditEntries: Iterable[ujson.Value], traceId: String): Unit = {
    val foundEntries = findAuditEntriesWithTraceId(auditEntries, traceId)
    foundEntries.size shouldBe 0
  }

  private def findAuditEntriesWithTraceId(auditEntries: Iterable[ujson.Value], traceId: String): List[ujson.Value] = {
    val expectedHeader = traceIdHeaderName(traceId)
    auditEntries.filter(_("headers").arr.exists(_.str == expectedHeader)).toList
  }

  private def traceIdHeaderName(traceId: String) = s"test-trace-id-$traceId"


}
