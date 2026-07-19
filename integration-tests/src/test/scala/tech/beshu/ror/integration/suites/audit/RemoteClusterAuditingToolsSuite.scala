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
import org.scalatest.time.{Millis, Span}
import tech.beshu.ror.integration.suites.base.BaseAuditingToolsSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.ContainerOps.*
import tech.beshu.ror.utils.containers.EsClusterSettings.positiveInt
import tech.beshu.ror.utils.containers.SecurityType.NoSecurityCluster
import tech.beshu.ror.utils.containers.dependencies.*
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.OsUtils.{CurrentOs, ignoreOnWindows}
import tech.beshu.ror.utils.misc.{OsUtils, Version}

import java.util.UUID
import scala.concurrent.duration.*

class RemoteClusterAuditingToolsSuite
    extends BaseAuditingToolsSuite
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport {

  private val isDataStreamSupported = Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)

  override implicit val rorSettingsFileName: String = {
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
        numberOfInstances = positiveInt(2),
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
          val name = s"AUDIT_${index + 1}"
          es(name, auditEsContainer)
        }
      case CurrentOs.OtherThanWindows =>
        proxiedContainers.zipWithIndex.map { case (proxiedContainer, index) =>
          val name = s"AUDIT_${index + 1}"
          toxiproxy(name, proxiedContainer)
        }
    }
  }

  override lazy val destNodesClientProviders: NonEmptyList[ClientProvider] =
    NonEmptyList.fromListUnsafe(auditEsContainers)

  override protected def baseRorSettingsYaml: String = resolvedRorSettingsFile.contentAsString

  override protected def baseAuditDataStreamName: Option[String] =
    Option.when(isDataStreamSupported)("audit_data_stream")

  // Adding the ES cluster fields is enabled in the /cluster_auditing_tools/readonlyrest.yml settings file (`DefaultAuditLogSerializerV2` is used)
  override def assertForEveryAuditEntry(entry: JSON): Unit = {
    entry("es_node_name").str shouldBe "ROR_SINGLE_1"
    entry("es_cluster_name").str shouldBe "ROR_SINGLE"
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    proxiedContainers.foreach(_.enableNetwork())
  }

  // This test suite does not execute on Windows: there is currently no Windows version of ToxiproxyContainer
  ignoreOnWindows {
    "ROR remote audit cluster mode" should {
      "report audit events in round-robin mode, even when some nodes are unreachable" in {
        forceReloadFreshEngine(baseRorSettingsYaml)
        val auditNode1 = proxiedContainers(0)
        val auditNode2 = proxiedContainers(1)

        val id1 = sendTracedRequest("phase-1")
        auditShouldContain(List(id1))

        auditNode1.disableNetwork()

        val id2 = sendTracedRequest("phase-2")
        auditShouldContain(List(id2))

        auditNode2.disableNetwork()

        val id3 = sendTracedRequest("phase-3")
        assertNoAuditDeliveryWhileDown(id3)

        auditNode1.enableNetwork()

        // node2 is still down; the round-robin client may attempt it first (connection timeout ~14s),
        // so we probe until an event lands rather than asserting immediately
        waitUntilAuditSinkIsBackOnline(atMost = 15.seconds)

        val id4 = sendTracedRequest("phase-4")
        auditShouldContain(List(id4))
      }
      "report audit events in failover mode, even when some nodes are unreachable" in {
        val configWithAuditFailover = configWithReplacements(baseRorSettingsYaml, Map("round-robin" -> "failover"))
        forceReloadFreshEngine(configWithAuditFailover)
        val auditNode1 = proxiedContainers(0)
        val auditNode2 = proxiedContainers(1)

        val id1 = sendTracedRequest("phase-1")
        auditShouldContain(List(id1))

        auditNode1.disableNetwork()

        val id2 = sendTracedRequest("phase-2")
        auditShouldContain(List(id2))

        auditNode2.disableNetwork()

        val id3 = sendTracedRequest("phase-3")
        assertNoAuditDeliveryWhileDown(id3)

        auditNode1.enableNetwork()

        // the circuit of node1 stays open for at most ~3.4s after the all-nodes-down phase,
        // so together with the probe interval and the audit index refresh, 15s is a safe bound
        waitUntilAuditSinkIsBackOnline(atMost = 15.seconds)

        val id4 = sendTracedRequest("phase-4")
        auditShouldContain(List(id4))
      }
      "handle audit settings reload when all nodes are unreachable and ignore_es_connectivity_problems is enabled" in {
        val auditNode1 = proxiedContainers(0)
        val auditNode2 = proxiedContainers(1)

        forceReloadFreshEngine(baseRorSettingsYaml)

        auditNode1.disableNetwork()
        auditNode2.disableNetwork()

        val updatedConfig: String = configWithReplacements(
          config = baseRorSettingsYaml,
          replacements = Map("ignore_es_connectivity_problems: false" -> "ignore_es_connectivity_problems: true")
        )

        val response = rorApiManager.updateRorInIndexSettings(updatedConfig)
        if (isDataStreamSupported) {
          // the data stream output has to verify/create the data stream upfront,
          // so the reload fails despite the ignored connectivity check
          response.forceFailure(
            s"Unable to configure audit output using a data stream in remote cluster ${auditNodeAddressFromConfig(auditNode1)}, ${auditNodeAddressFromConfig(auditNode2)}. " +
              s"Details: [Unable to determine if data stream audit_data_stream exists.]"
          )
        } else {
          // the index output is created lazily, so with the connectivity check ignored the reload succeeds
          response.forceOkStatus()
        }
      }
      "reload audit settings when one node is unreachable and ignore_es_connectivity_problems is disabled" in {
        val auditNode1 = proxiedContainers(0)
        val auditNode2 = proxiedContainers(1)
        auditNode1.enableNetwork()
        auditNode2.enableNetwork()
        // assert config is valid
        forceReloadFreshEngine(baseRorSettingsYaml)

        auditNode1.disableNetwork()

        rorApiManager.updateRorInIndexSettings(baseRorSettingsYaml).forceOkStatus()
      }
      "fail to reload audit settings when all nodes are unreachable and ignore_es_connectivity_problems is disabled" in {
        val auditNode1 = proxiedContainers(0)
        val auditNode2 = proxiedContainers(1)
        auditNode1.enableNetwork()
        auditNode2.enableNetwork()
        // assert config is valid
        forceReloadFreshEngine(baseRorSettingsYaml)

        auditNode1.disableNetwork()
        auditNode2.disableNetwork()

        rorApiManager
          .updateRorInIndexSettings(baseRorSettingsYaml)
          .forceFailure(
            s"Audit cluster healthcheck failed for remote cluster ${auditNodeAddressFromConfig(auditNode1)}, ${auditNodeAddressFromConfig(auditNode2)}. " +
              s"Details: No health node detected in remote cluster. " +
              s"Unexpected connection error from audit node: ${auditNodeAddressFromConfig(auditNode1)}, " +
              s"Unexpected connection error from audit node: ${auditNodeAddressFromConfig(auditNode2)}"
          )
      }
    }
  }

  private def forceReloadFreshEngine(config: String): Unit = {
    // the unique comment makes the settings differ from the previously loaded ones, so the reload
    // always creates a fresh engine (and fresh audit sink clients - no circuit breaker
    // or dead-host state leaks between tests)
    rorApiManager
      .updateRorInIndexSettings(s"# test-engine-id: ${UUID.randomUUID()}\n$config")
      .forceOkStatus()
  }

  private def sendTracedRequest(prefix: String): String = {
    val traceId = s"$prefix-${UUID.randomUUID()}"
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

  private def auditShouldContain(traceIds: List[String]): Unit = {
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

  // audit submission is fire-and-forget (Monix runAsync): the task may be delayed on a loaded
  // CI machine and execute after nodes come back online. Checking consistently while nodes are
  // still down proves the event cannot reach audit - any delivery attempt is blocked by Toxiproxy.
  private def assertNoAuditDeliveryWhileDown(traceId: String): Unit = {
    consistently(during = 3.seconds) {
      forEachAuditManager { adminAuditManager =>
        findAuditEntriesWithTraceId(adminAuditManager.getEntries.force().jsons, traceId) shouldBe empty
      }
    }
  }

  // Sends sacrificial probe events until one lands in audit, proving the sink has recovered.
  // A delivered event guarantees events sent afterwards will not be lost.
  private def waitUntilAuditSinkIsBackOnline(atMost: FiniteDuration): Unit = {
    val probeTraceIds = scala.collection.mutable.ListBuffer.empty[String]
    forEachAuditManager { adminAuditManager =>
      eventually(timeout(Span(atMost.toMillis, Millis)), interval(Span(500, Millis))) {
        probeTraceIds += sendTracedRequest(prefix = "probe")
        val auditEntries = adminAuditManager.getEntries.force().jsons
        probeTraceIds.exists(id => findAuditEntriesWithTraceId(auditEntries, id).nonEmpty) shouldBe true
      }
    }
  }

  private def consistently(during: FiniteDuration, interval: FiniteDuration = 500.millis)(
      assertion: => Unit
  ): Unit = {
    val deadline = during.fromNow
    assertion
    while (deadline.hasTimeLeft()) {
      Thread.sleep(interval.toMillis)
      assertion
    }
  }

  private def findAuditEntryWithTraceId(auditEntries: Iterable[ujson.Value], traceId: String) = {
    val foundEntries = findAuditEntriesWithTraceId(auditEntries, traceId)
    withClue(s"Didn't found expected audit entry with traceId [$traceId] in audit entries $auditEntries") {
      foundEntries.size should be(1)
      foundEntries.head
    }
  }

  private def findAuditEntriesWithTraceId(auditEntries: Iterable[ujson.Value], traceId: String): List[ujson.Value] = {
    val expectedHeader = traceIdHeaderName(traceId)
    auditEntries.filter(_("headers").arr.exists(_.str == expectedHeader)).toList
  }

  private def traceIdHeaderName(traceId: String) = s"test-trace-id-$traceId"

  private def configWithReplacements(config: String, replacements: Map[String, String]) = {
    val newConfig = replacements.foldLeft(config) { case (config, (key, value)) =>
      config.replaceAll(key, value)
    }
    newConfig should not equal config
    newConfig
  }

  private def auditNodeAddressFromConfig(proxyContainer: ToxiproxyContainer[_]) = {
    s"http://${proxyContainer.ipAddressFromFirstNetwork.get}:${ToxiproxyContainer.proxiedPort}"
  }

}
