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

import tech.beshu.ror.integration.suites.base.BaseAuditingToolsSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer
import tech.beshu.ror.utils.misc.Version

class LocalClusterAuditingToolsSuite
  extends BaseAuditingToolsSuite
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport {

  private val isDataStreamSupported = Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)

  override implicit val rorConfigFileName: String = {
    if (isDataStreamSupported) {
      "/ror_audit/enabled_auditing_tools/readonlyrest.yml"
    } else {
      "/ror_audit/enabled_auditing_tools/readonlyrest_audit_index.yml"
    }
  }

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override lazy val destNodeClientProvider: ClientProvider = this

  override def baseRorConfig: String = resolvedRorConfigFile.contentAsString

  override protected def baseAuditDataStreamName: Option[String] =
    Option.when(Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0))("audit_data_stream")

  // Adding the ES cluster fields is disabled in the /enabled_auditing_tools/readonlyrest.yml config file (`DefaultAuditLogSerializerV1` is used)
  override def assertForEveryAuditEntry(entry: JSON): Unit = {
    entry.obj.get("es_node_name") shouldBe None
    entry.obj.get("es_cluster_name") shouldBe None
  }

}
