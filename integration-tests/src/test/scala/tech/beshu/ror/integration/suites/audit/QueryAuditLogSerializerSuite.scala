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
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager, RorApiManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import tech.beshu.ror.utils.TestUjson.ujson

import java.util.UUID
import scala.language.postfixOps

class QueryAuditLogSerializerSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterEach
    with CustomScalaTestMatchers {

  override implicit val rorSettingsFileName: String = "/ror_audit/query_audit_log_serializer/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  private lazy val auditIndexManager = new AuditIndexManager(adminClient, esVersionUsed, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    auditIndexManager.truncate()
  }

  "Request" should {
    "be audited" when {
      "user metadata context for failed login" in {
        val user1MetadataManager = new RorApiManager(basicAuthClient("user2", "dev"), esVersionUsed)

        val result = user1MetadataManager.fetchUserMetadata("ent")

        result should have statusCode 403

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size should be (1)
        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "FORBIDDEN"
        firstEntry("user").str should be("user2")
        firstEntry("block").str should include("""deny all indices""")
        firstEntry("content").str shouldBe ""
      }
      "user metadata context" in {
        val user1MetadataManager = new RorApiManager(authHeader("X-Auth-Token", "user1-proxy-id"), esVersionUsed)

        val correlationId = UUID.randomUUID().toString
        val result = user1MetadataManager.fetchUserMetadata("ent", Some(correlationId))

        result should have statusCode 200

        result.responseJson should be(ujson.read(
          s"""{
             |  "type": "USER_WITH_GROUPS",
             |  "correlation_id": "$correlationId",
             |  "groups": [
             |    {
             |      "username": "user1-proxy-id",
             |      "group": {
             |        "id": "group1",
             |        "name": "group1"
             |      },
             |      "kibana": {
             |        "access":"unrestricted"
             |      }
             |    }
             |  ]
             |}""".stripMargin))

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size should be (1)

        val firstEntry = auditIndexManager.getEntries.jsons(0)
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("user").str should be("user1-proxy-id")
        firstEntry("block").str should include("""name: 'Allowed only for group1'""")
        firstEntry("content").str shouldBe ""
      }
      "rule 1 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 200

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size should be (1)

        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("user").str should be("user")
        firstEntry("block").str should include("name: 'Rule 1'")
        firstEntry("content").str shouldBe ""
      }
      "no rule is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "wrong"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 403

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size should be (1)

        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "FORBIDDEN"
        firstEntry("content").str shouldBe ""
      }
    }
    "not be audited" when {
      "rule 2 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"), esVersionUsed)
        val response = indexManager.getIndex("facebook")
        response should have statusCode 200

        auditIndexManager.hasNoEntries
      }
    }
  }
}
