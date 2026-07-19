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
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

// Configures a `pipeline:` name that is never created in ES, to lock in the current failure
// behavior: the audited request itself is unaffected (audit submission is fire-and-forget), and
// the audit document that ES rejects (400 "pipeline with id [...] does not exist") never lands.
class AuditPipelineMissingIntegrationSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers
    with Eventually {

  override implicit val rorSettingsFileName: String =
    "/ror_audit/enabled_auditing_tools_with_pipeline/readonlyrest_missing_pipeline.yml"

  // Deliberately does NOT create the "missing_pipeline" pipeline referenced in the settings above.
  override val nodeDataInitializer = Some(ElasticsearchTweetsInitializer)

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private lazy val indexAuditManager = new AuditIndexManager(adminClient, esVersionUsed, "audit_index_missing_pipeline")

  private lazy val dataStreamAuditManager =
    new AuditIndexManager(adminClient, esVersionUsed, "audit_data_stream_missing_pipeline")

  "A request audited through a sink with a non-existent pipeline" should {
    "still succeed on the client side" in {
      val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
      indexManager.getIndex("twitter") should have statusCode 200
    }
    "not create the index-based sink's index (every write is rejected by ES)" in {
      indexAuditManager.getEntries should have statusCode 404
    }
    "not leave any entry in the data-stream-based sink (data stream exists, writes still rejected)" in {
      dataStreamAuditManager.getEntries.jsons shouldBe empty
    }
    "log an ES-level error naming the missing pipeline, so the failure is observable in the ES log" in {
      eventually {
        val logs = targetEs.container.getLogs
        logs should include("Some failures flushing the BulkProcessor")
        logs should include("pipeline with id [missing_pipeline] does not exist")
      }
    }
  }

}
