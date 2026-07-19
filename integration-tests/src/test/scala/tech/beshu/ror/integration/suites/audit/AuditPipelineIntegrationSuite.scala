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
import tech.beshu.ror.utils.elasticsearch.{
  AuditIndexManager,
  AuditIngestPipelineInitializer,
  ElasticsearchTweetsInitializer,
  IndexManager
}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

// Proves the `pipeline` audit output setting is not just parsed and threaded through config
// (already covered by unit tests), but actually reaches ES: the pipeline is a `set` processor
// that stamps `pipeline_applied: true`, and we assert that field lands on the indexed audit doc,
// for both the index-based and data-stream-based sinks.
class AuditPipelineIntegrationSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers
    with Eventually {

  private val pipelineName = "audit_pipeline"

  override implicit val rorSettingsFileName: String =
    "/ror_audit/enabled_auditing_tools_with_pipeline/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(
    new ComposedElasticsearchNodeDataInitializer(
      ElasticsearchTweetsInitializer,
      new AuditIngestPipelineInitializer(pipelineName)
    )
  )

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private lazy val indexAuditManager = new AuditIndexManager(adminClient, esVersionUsed, "audit_index_with_pipeline")

  private lazy val dataStreamAuditManager =
    new AuditIndexManager(adminClient, esVersionUsed, "audit_data_stream_with_pipeline")

  "Configured ingest pipeline" should {
    "be applied to audit documents submitted by the index-based sink" in {
      val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
      indexManager.getIndex("twitter") should have statusCode 200

      eventually {
        val entries = indexAuditManager.getEntries.jsons
        entries should not be empty
        entries.foreach(_("pipeline_applied").bool shouldBe true)
      }
    }
    "be applied to audit documents submitted by the data-stream-based sink" in {
      val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
      indexManager.getIndex("twitter") should have statusCode 200

      eventually {
        val entries = dataStreamAuditManager.getEntries.jsons
        entries should not be empty
        entries.foreach(_("pipeline_applied").bool shouldBe true)
      }
    }
  }

}
