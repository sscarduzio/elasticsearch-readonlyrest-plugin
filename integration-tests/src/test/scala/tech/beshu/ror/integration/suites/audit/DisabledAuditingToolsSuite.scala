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

import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class DisabledAuditingToolsSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterEach
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/ror_audit/disabled_auditing_tools/readonlyrest.yml"

  override val nodeDataInitializer = Some(ElasticsearchTweetsInitializer)

  private lazy val auditIndexManager = new AuditIndexManager(adminClient, esVersionUsed, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    auditIndexManager.truncate()
  }

  "Request" should {
    "not be audited" when {
      "rule 1 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 200

        auditIndexManager.getEntries should have statusCode 404
      }
      "rule 2 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"), esVersionUsed)
        val response = indexManager.getIndex("facebook")
        response should have statusCode 200

        auditIndexManager.getEntries should have statusCode 404
      }
      "no rule is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "wrong"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 403

        auditIndexManager.getEntries should have statusCode 404
      }
    }
  }
}
