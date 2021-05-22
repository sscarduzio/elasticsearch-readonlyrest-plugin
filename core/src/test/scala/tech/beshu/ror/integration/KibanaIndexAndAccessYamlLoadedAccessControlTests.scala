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
package tech.beshu.ror.integration

import com.softwaremill.sttp.Method
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inside
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.{Action, Group, IndexName, KibanaAccess, UriPath, User}
import tech.beshu.ror.mocks.MockRequestContext
import eu.timepit.refined.auto._
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader
import tech.beshu.ror.utils.uniquelist.UniqueList

class KibanaIndexAndAccessYamlLoadedAccessControlTests extends AnyWordSpec
  with BaseYamlLoadedAccessControlTest
  with MockFactory with Inside  {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Template Tenancy"
      |    verbosity: error
      |    kibana_access: admin
      |    kibana_index: ".kibana_template"
      |
      |  - name: "Read-Write access with RoR custom kibana index"
      |    indices: [".kibana_ror_custom", ".reporting.kibana_ror_custom-*", "logstash*", "readonlyrest_audit-*"]
      |    kibana_index: ".kibana_ror_custom"
      |    kibana_access: rw
      |    groups: ["RW_ror_custom"]
      |
      |  users:
      |  - username: testuser_ro_master
      |    auth_key: testuser_ro_master:XXXX
      |    groups: ["RO_master"]
      |
      |  - username: testuser_ro_master_rw_custom
      |    auth_key: testuser_ro_master_rw_custom:XXXX
      |    groups: ["RO_master", "RW_ror_custom"]
      |
      """.stripMargin

  "An ACL" when {
    "kibana index and kibana access rules are used" should {
      "allow to proceed" in {
        val request = MockRequestContext.indices.copy(
          action = Action("indices:monitor/*"),
          filteredIndices = Set(IndexName(".readonlyrest"))
        )

        val result = acl.handleRegularRequest(request).runSyncUnsafe()

        result.history should have size 1
        inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
          block.name should be(Block.Name("Template Tenancy"))
          assertBlockContext(
            kibanaIndex = Some(IndexName(".kibana_template")),
            kibanaAccess = Some(KibanaAccess.Admin),
            indices = Set(IndexName(".readonlyrest")),
          ) {
            blockContext
          }
        }
      }
    }
    "test" in {
      val request = MockRequestContext.indices.copy(
        headers = Set(basicAuthHeader("testuser_ro_master_rw_custom:XXXX")),
        uriPath = UriPath("/.kibana_ror_custom/_doc/dashboard:d3d40550-b889-11eb-a1e1-914af9365d47"),
        method = Method("PUT"),
        action = Action("indices:data/write/index"),
        filteredIndices = Set(IndexName(".kibana_ror_custom"))
      )

      val result = acl.handleRegularRequest(request).runSyncUnsafe()

      result.history should have size 2
      inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
        block.name should be(Block.Name("Read-Write access with RoR custom kibana index"))
        assertBlockContext(
          loggedUser = Some(DirectlyLoggedUser(User.Id("testuser_ro_master_rw_custom"))),
          currentGroup = Some(Group("RW_ror_custom")),
          availableGroups = UniqueList.of(Group("RW_ror_custom")),
          kibanaIndex = Some(IndexName(".kibana_ror_custom")),
          kibanaAccess = Some(KibanaAccess.RW),
          indices = Set(IndexName(".kibana_ror_custom")),
        ) {
          blockContext
        }
      }
    }
  }

}
