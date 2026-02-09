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

import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.{RegularRequestResult, UserMetadataRequestResult}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.metadata.{KibanaPolicy, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.KibanaApp.FullNameKibanaApp
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList

class KibanaIndexAndAccessYamlLoadedAccessControlTests extends AnyWordSpec
  with BaseYamlLoadedAccessControlTest
  with Inside {

  override protected def settingsYaml: String =
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
      |    kibana_hide_apps: [ "Enterprise Search|Overview", "Observability", "/^Analytics\\|(?!(Maps)$).*$/" ]
      |    kibana_index: '.kibana_@{user}'
      |
      |  - name: ADMIN_GRP
      |    groups: [ Administrators ]
      |    kibana_access: admin
      |    kibana_hide_apps: [ "Enterprise Search|Overview", "Observability", "/^Analytics\\|(?!(Maps)$).*$/" ]
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
      "allow to proceed" when {
        "indices monitor request is called" in {
          val request = MockRequestContext.indices
            .withHeaders(basicAuthHeader("john:dev"))
            .copy(
              action = Action("indices:monitor/*"),
              filteredIndices = Set(requestedIndex(".readonlyrest"))
            )

          val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result) { case RegularRequestResult.Allow(blockContext) =>
            blockContext.block.name should be(Block.Name("Template Tenancy"))
            assertBlockContext(blockContext)(
              loggedUser = Some(DirectlyLoggedUser(User.Id("john"))),
              indices = Set(requestedIndex(".readonlyrest")),
              kibanaPolicy = Some(KibanaPolicy.default.copy(
                access = KibanaAccess.Admin,
                index = Some(kibanaIndexName(".kibana_template")),
              )),
            )
          }
        }
        "component template creation request is called (in case of `admin` kibana access)" in {
          val request = MockRequestContext.nonIndices.copy(
            restRequest = MockRestRequest(
              allHeaders = Set(basicAuthHeader("john:dev")),
              path = UriPath.from("/_component_template/test")
            ),
            action = Action("cluster:admin/component_template/put"),
          )

          val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result) { case RegularRequestResult.Allow(blockContext) =>
            blockContext.block.name should be(Block.Name("Template Tenancy"))
            assertBlockContext(blockContext)(
              loggedUser = Some(DirectlyLoggedUser(User.Id("john"))),
              kibanaPolicy = Some(KibanaPolicy.default.copy(
                access = KibanaAccess.Admin,
                index = Some(kibanaIndexName(".kibana_template")),
              )),
            )
          }
        }
        "component template creation request is called (in case of `rw` kibana access)" in {
          val request = MockRequestContext.nonIndices.copy(
            restRequest = MockRestRequest(
              allHeaders = Set(basicAuthHeader("testuser_ro_master_rw_custom:XXXX")),
              path = UriPath.from("/_component_template/test")
            ),
            action = Action("cluster:admin/component_template/put"),
          )

          val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result) { case RegularRequestResult.Allow(blockContext) =>
            blockContext.block.name should be(Block.Name("Read-Write access with RoR custom kibana index"))
            assertBlockContext(blockContext)(
              loggedUser = Some(DirectlyLoggedUser(User.Id("testuser_ro_master_rw_custom"))),
              currentGroup = Some(GroupId("RW_ror_custom")),
              availableGroups = UniqueList.of(group("RW_ror_custom")),
              kibanaPolicy = Some(KibanaPolicy.default.copy(
                access = KibanaAccess.RW,
                index = Some(kibanaIndexName(".kibana_ror_custom")),
              )),
            )
          }
        }
        "index template creation request is called (in case of `admin` kibana access)" in {
          val request = MockRequestContext.nonIndices.copy(
            restRequest = MockRestRequest(
              allHeaders = Set(basicAuthHeader("john:dev")),
              path = UriPath.from("/_component_template/test")
            ),
            action = Action("cluster:admin/component_template/put"),
          )

          val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result) { case RegularRequestResult.Allow(blockContext) =>
            blockContext.block.name should be(Block.Name("Template Tenancy"))
            assertBlockContext(blockContext)(
              loggedUser = Some(DirectlyLoggedUser(User.Id("john"))),
              kibanaPolicy = Some(KibanaPolicy.default.copy(
                access = KibanaAccess.Admin,
                index = Some(kibanaIndexName(".kibana_template")),
              )),
            )
          }
        }
      }
    }
    "kibana index and kibana access rules are used (but the index one is behind the access one)" should {
      "allow to proceed" in {
        val request = MockRequestContext.indices.copy(
          restRequest = MockRestRequest(
            allHeaders = Set(basicAuthHeader("testuser_ro_master_rw_custom:XXXX")),
            path = UriPath.from("/.kibana_ror_custom/_doc/dashboard:d3d40550-b889-11eb-a1e1-914af9365d47"),
          ),
          action = Action("indices:data/write/index"),
          filteredIndices = Set(requestedIndex(".kibana_ror_custom"))
        )

        val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

        inside(result) { case RegularRequestResult.Allow(blockContext) =>
          blockContext.block.name should be(Block.Name("Read-Write access with RoR custom kibana index"))
          assertBlockContext(blockContext)(
            loggedUser = Some(DirectlyLoggedUser(User.Id("testuser_ro_master_rw_custom"))),
            currentGroup = Some(GroupId("RW_ror_custom")),
            availableGroups = UniqueList.of(group("RW_ror_custom")),
            indices = Set(requestedIndex(".kibana_ror_custom")),
            kibanaPolicy = Some(KibanaPolicy.default.copy(
              access = KibanaAccess.RW,
              index = Some(kibanaIndexName(".kibana_ror_custom")),
            )),
          )
        }
      }
    }
    "kibana index and kibana access rules are used (when current user metadata request was called first)" should {
      "allow to proceed" in {
        val loginRequest = MockRequestContext.metadata.withHeaders(basicAuthHeader("admin:dev"))

        val (loginResult, _) = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()

        inside(loginResult) { case UserMetadataRequestResult.Allow(metadata@UserMetadata.WithGroups(_)) =>
          metadata.groupsMetadata.keys.toList should be(GroupId("Administrators") :: GroupId("Infosec") :: Nil)

          val adminMetadata = metadata.groupsMetadata(GroupId("Administrators"))
          adminMetadata.metadataOrigin.blockContext.block.name should be(Block.Name("ADMIN_GRP"))
          adminMetadata.loggedUser should be(DirectlyLoggedUser(User.Id("admin")))
          adminMetadata.userOrigin should be(None)
          adminMetadata.kibanaPolicy should be(Some(KibanaPolicy(
            access = KibanaAccess.Admin,
            index = Some(kibanaIndexName(".kibana_admins")),
            templateIndex = None,
            hiddenApps = Set(
              FullNameKibanaApp("Enterprise Search|Overview"),
              FullNameKibanaApp("Observability"),
              kibanaAppRegex("/^Analytics\\|(?!(Maps)$).*$/")
            ),
            allowedApiPaths = Set.empty,
            genericMetadata = None
          )))

          val infosecMetadata = metadata.groupsMetadata(GroupId("Infosec"))
          infosecMetadata.metadataOrigin.blockContext.block.name should be(Block.Name("Infosec"))
          infosecMetadata.loggedUser should be(DirectlyLoggedUser(User.Id("admin")))
          infosecMetadata.userOrigin should be(None)
          infosecMetadata.kibanaPolicy should be(Some(KibanaPolicy(
            access = KibanaAccess.RW,
            index = Some(kibanaIndexName(".kibana_infosec")),
            templateIndex = None,
            hiddenApps = Set(
              FullNameKibanaApp("Enterprise Search|Overview"),
              FullNameKibanaApp("Observability")
            ),
            allowedApiPaths = Set.empty,
            genericMetadata = None
          )))
        }

        val request = MockRequestContext.indices.copy(
          restRequest = MockRestRequest(
            method = Method.PUT,
            allHeaders = Set(basicAuthHeader("admin:dev"), currentGroupHeader("Administrators")),
            path = UriPath.from("/.kibana_admins/_create/index-pattern:3b2fa1b0-bcb2-11eb-a20e-8daf1d07a2b2")
          ),
          action = Action("indices:data/write/index"),
          filteredIndices = Set(requestedIndex(".kibana_admins"))
        )

        val (result, _) = acl.handleRegularRequest(request).runSyncUnsafe()

        inside(result) { case RegularRequestResult.Allow(blockContext) =>
          blockContext.block.name should be(Block.Name("ADMIN_GRP"))
          assertBlockContext(blockContext)(
            loggedUser = Some(DirectlyLoggedUser(User.Id("admin"))),
            currentGroup = Some(GroupId("Administrators")),
            availableGroups = UniqueList.of(group("Administrators")),
            indices = Set(requestedIndex(".kibana_admins")),
            kibanaPolicy = Some(KibanaPolicy.default.copy(
              access = KibanaAccess.Admin,
              index = Some(kibanaIndexName(".kibana_admins")),
              hiddenApps = Set(
                FullNameKibanaApp("Observability"),
                FullNameKibanaApp("Enterprise Search|Overview"),
                kibanaAppRegex("/^Analytics\\|(?!(Maps)$).*$/")
              )
            )),
          )
        }
      }
    }
  }

}
