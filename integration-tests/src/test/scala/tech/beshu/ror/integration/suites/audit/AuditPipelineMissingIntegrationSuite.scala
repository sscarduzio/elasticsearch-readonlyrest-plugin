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
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

class AuditPipelineMissingIntegrationSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers
    with Eventually {

  private val isDataStreamSupported = Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)

  override implicit val rorSettingsFileName: String =
    if (isDataStreamSupported) "/ror_audit/enabled_auditing_tools_with_pipeline/readonlyrest_missing_pipeline.yml"
    else "/ror_audit/enabled_auditing_tools_with_pipeline/readonlyrest_missing_pipeline_audit_index.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  "An audit output with an ingest pipeline that does not exist" should {
    "not affect the audited request" in {
      sendAuditedRequest()
    }
    "not affect the other audit outputs" in {
      sendAuditedRequest()

      new AuditIndexManager(
        adminClient,
        esVersionUsed,
        "audit_index_next_to_missing_pipeline"
      ).getEntries.jsons should not be empty
    }
    "log the rejection with the index of the output and the ES error" in {
      sendAuditedRequest()

      eventually {
        rejectionLogLines("audit_index_missing_pipeline") should not be empty
      }
    }
    "not store the audit events in the index" in {
      sendAuditedRequest()

      eventually {
        rejectionLogLines("audit_index_missing_pipeline") should not be empty
      }
      new AuditIndexManager(
        adminClient,
        esVersionUsed,
        "audit_index_missing_pipeline"
      ).getEntries should have statusCode 404
    }
    if (isDataStreamSupported) {
      "not store the audit events in the data stream" in {
        sendAuditedRequest()

        eventually {
          rejectionLogLines("audit_data_stream_missing_pipeline") should not be empty
        }
        new AuditIndexManager(adminClient, esVersionUsed, "audit_data_stream_missing_pipeline").hasNoEntries
      }
    }
  }

  private def sendAuditedRequest(): Unit = {
    val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
    indexManager.getIndex("twitter") should have statusCode 200
  }

  private def rejectionLogLines(indexName: String): List[String] =
    targetEs.container.getLogs.linesIterator
      .filter(line =>
        line.contains(s"audit event(s) for [$indexName]") &&
          line.contains("pipeline with id [missing_pipeline] does not exist")
      )
      .toList

}
