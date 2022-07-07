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
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.{RegularRequestResult, UserMetadataRequestResult}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader
import tech.beshu.ror.utils.uniquelist.UniqueList
import tech.beshu.ror.utils.TestsUtils._

class KibanaIndexAndAccessYamlLoadedAccessControlTests extends AnyWordSpec
  with BaseYamlLoadedAccessControlTest
  with MockFactory with Inside {

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
      |    auth_key: john:dev
      |
      |  - name: "Read-Write access with RoR custom kibana index"
      |    indices: [".kibana_ror_custom", ".reporting.kibana_ror_custom-*", "logstash*", "readonlyrest_audit-*"]
      |    kibana_index: ".kibana_ror_custom"
      |    kibana_access: rw
      |    groups: ["RW_ror_custom"]
      |
      |  - name: PERSONAL_GRP
      |    groups: [ Personal ]
      |    kibana_access: rw
      |    kibana_hide_apps: [ "Enterprise Search|Overview", "Observability" ]
      |    kibana_index: '.kibana_@{user}'
      |
      |  - name: ADMIN_GRP
      |    groups: [ Administrators ]
      |    kibana_access: admin
      |    kibana_hide_apps: [ "Enterprise Search|Overview", "Observability" ]
      |    kibana_index: '.kibana_admins'
      |
      |  - name: Infosec
      |    groups: [ Infosec ]
      |    kibana_access: rw
      |    kibana_hide_apps: [ "Enterprise Search|Overview", "Observability" ]
      |    kibana_index: .kibana_infosec
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
      |  - username: admin
      |    auth_key: admin:dev
      |    groups: [Administrators, Infosec]
      |
      |  - username: user1
      |    auth_key: user1:dev
      |    groups: [Administrators, Personal, Infosec]
      |
      """.stripMargin

  "An ACL" when {
    "kibana index and kibana access rules are used" should {
      "allow to proceed" in {
        val request = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("john:dev")),
          action = Action("indices:monitor/*"),
          filteredIndices = Set(clusterIndexName(".readonlyrest"))
        )

        val result = acl.handleRegularRequest(request).runSyncUnsafe()

        result.history should have size 1
        inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
          block.name should be(Block.Name("Template Tenancy"))
          assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("john"))),
            kibanaIndex = Some(clusterIndexName(".kibana_template")),
            kibanaAccess = Some(KibanaAccess.Admin),
            indices = Set(clusterIndexName(".readonlyrest")),
          ) {
            blockContext
          }
        }
      }
    }
    "kibana index and kibana access rules are used (but the index one is behind the access one)" should {
      "allow to proceed" in {
        val request = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("testuser_ro_master_rw_custom:XXXX")),
          uriPath = UriPath("/.kibana_ror_custom/_doc/dashboard:d3d40550-b889-11eb-a1e1-914af9365d47"),
          method = Method("PUT"),
          action = Action("indices:data/write/index"),
          filteredIndices = Set(clusterIndexName(".kibana_ror_custom"))
        )

        val result = acl.handleRegularRequest(request).runSyncUnsafe()

        result.history should have size 2
        inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
          block.name should be(Block.Name("Read-Write access with RoR custom kibana index"))
          assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("testuser_ro_master_rw_custom"))),
            currentGroup = Some(Group("RW_ror_custom")),
            availableGroups = UniqueList.of(Group("RW_ror_custom")),
            kibanaIndex = Some(clusterIndexName(".kibana_ror_custom")),
            kibanaAccess = Some(KibanaAccess.RW),
            indices = Set(clusterIndexName(".kibana_ror_custom")),
          ) {
            blockContext
          }
        }
      }
    }
    "kibana index and kibana access rules are used (when current user metadata request was called first)" should {
      "allow to proceed" in {
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(basicAuthHeader("admin:dev"))
        )

        val loginResult = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()

        inside(loginResult.result) { case UserMetadataRequestResult.Allow(metadata, _) =>
          metadata should be(UserMetadata(
            loggedUser = Some(DirectlyLoggedUser(User.Id("admin"))),
            currentGroup = Some(Group("Administrators")),
            availableGroups = UniqueList.of(Group("Administrators"), Group("Infosec")),
            kibanaIndex = Some(clusterIndexName(".kibana_admins")),
            kibanaTemplateIndex = None,
            hiddenKibanaApps = Set(KibanaApp("Enterprise Search|Overview"), KibanaApp("Observability")),
            kibanaAccess = Some(KibanaAccess.Admin),
            userOrigin = None,
            jwtToken = None
          ))
        }

        val request = MockRequestContext.indices.copy(
          headers = Set(
            basicAuthHeader("admin:dev"),
            currentGroupHeader("Administrators")
          ),
          uriPath = UriPath("/.kibana_admins/_create/index-pattern:3b2fa1b0-bcb2-11eb-a20e-8daf1d07a2b2"),
          method = Method("PUT"),
          action = Action("indices:data/write/index"),
          isReadOnlyRequest = false,
          filteredIndices = Set(clusterIndexName(".kibana_admins"))
        )

        val result = acl.handleRegularRequest(loginRequest).runSyncUnsafe()

        result.history should have size 4
        inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
          block.name should be(Block.Name("ADMIN_GRP"))
          assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("admin"))),
            currentGroup = Some(Group("Administrators")),
            availableGroups = UniqueList.of(Group("Administrators")),
            kibanaIndex = Some(clusterIndexName(".kibana_admins")),
            kibanaAccess = Some(KibanaAccess.Admin),
            indices = Set(clusterIndexName(".kibana_admins")),
            hiddenKibanaApps = Set(KibanaApp("Observability"), KibanaApp("Enterprise Search|Overview"))
          ) {
            blockContext
          }
        }
      }
    }
  }

}
