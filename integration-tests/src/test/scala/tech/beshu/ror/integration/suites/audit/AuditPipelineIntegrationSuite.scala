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

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.containers.{ComposedElasticsearchNodeDataInitializer, ElasticsearchNodeDataInitializer}
import tech.beshu.ror.utils.elasticsearch.AuditIngestPipelineInitializer.markerField
import tech.beshu.ror.utils.elasticsearch.{
  AuditIndexManager,
  AuditIngestPipelineInitializer,
  ElasticsearchTweetsInitializer,
  IndexManager
}
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

class AuditPipelineIntegrationSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers
    with Eventually {

  private val isDataStreamSupported = Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)

  override implicit val rorSettingsFileName: String =
    if (isDataStreamSupported) "/ror_audit/enabled_auditing_tools_with_pipeline/readonlyrest.yml"
    else "/ror_audit/enabled_auditing_tools_with_pipeline/readonlyrest_audit_index.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(
    new ComposedElasticsearchNodeDataInitializer(
      ElasticsearchTweetsInitializer,
      new ComposedElasticsearchNodeDataInitializer(
        new AuditIngestPipelineInitializer("audit_pipeline_a", marker = "a"),
        new AuditIngestPipelineInitializer("audit_pipeline_b", marker = "b")
      )
    )
  )

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  "An audit output with an ingest pipeline" should {
    "store the audit events that its pipeline processed" in {
      sendAuditedRequest()

      eventually {
        markersIn("audit_index_pipeline_a") shouldBe Set(Some("a"))
      }
    }
    "use its own pipeline when the other outputs use different pipelines" in {
      sendAuditedRequest()

      eventually {
        markersIn("audit_index_pipeline_a") shouldBe Set(Some("a"))
        markersIn("audit_index_pipeline_b") shouldBe Set(Some("b"))
      }
    }
    if (isDataStreamSupported) {
      "store the audit events that its pipeline processed in a data stream" in {
        sendAuditedRequest()

        eventually {
          markersIn("audit_data_stream_pipeline_b") shouldBe Set(Some("b"))
        }
      }
    }
  }

  "An audit output without an ingest pipeline" should {
    "store the audit events that no pipeline processed, when other outputs use pipelines" in {
      sendAuditedRequest()

      eventually {
        markersIn("audit_index_without_pipeline") shouldBe Set(None)
      }
    }
  }

  private def sendAuditedRequest(): Unit = {
    val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
    indexManager.getIndex("twitter") should have statusCode 200
  }

  private def markersIn(indexName: String): Set[Option[String]] = {
    val entries = new AuditIndexManager(adminClient, esVersionUsed, indexName).getEntries.jsons
    entries should not be empty
    entries.map(_.obj.get(markerField).map(_.str)).toSet
  }

}
