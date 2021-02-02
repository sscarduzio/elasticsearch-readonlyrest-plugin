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
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult._
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueList

class CurrentUserMetadataAccessControlTests extends AnyWordSpec with BaseYamlLoadedAccessControlTest with MockFactory with Inside {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "User 1 - index1"
      |    users: ["user1"]
      |    groups: [group2, group3]
      |
      |  - name: "User 1 - index2"
      |    users: ["user1"]
      |    groups: [group2, group1]
      |
      |  - name: "User 2"
      |    users: ["user2"]
      |    groups: [group2, group3]
      |    uri_re: ^/_readonlyrest/metadata/current_user/?$
      |    kibana_index: "user2_kibana_index"
      |    kibana_hide_apps: ["user2_app1", "user2_app2"]
      |    kibana_access: ro
      |
      |  - name: "User 3"
      |    auth_key: "user3:pass"
      |    kibana_index: "user3_kibana_index"
      |    kibana_hide_apps: ["user3_app1", "user3_app2"]
      |
      |  - name: "User 4 - index1"
      |    users: ["user4"]
      |    actions: ["default-action"]
      |    kibana_index: "user4_group5_kibana_index"
      |    groups: [group5]
      |
      |  - name: "User 4 - index2"
      |    users: ["user4"]
      |    actions: ["default-action"]
      |    kibana_index: "user4_group6_kibana_index"
      |    groups: [group6, group5]
      |
      |  users:
      |
      |  - username: user1
      |    groups: ["group1", "group3"]
      |    auth_key: "user1:pass"
      |
      |  - username: user2
      |    groups: ["group2", "group4"]
      |    auth_key: "user2:pass"
      |
      |  - username: user4
      |    groups: ["group5", "group6"]
      |    auth_key: "user4:pass"
      |
    """.stripMargin

  "An ACL" when {
    "handling current user metadata kibana plugin request" should {
      "allow to proceed" when {
        "several blocks are matched" in {
          val request = MockRequestContext.metadata.copy(headers = Set(basicAuthHeader("user1:pass")))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 6
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user1".nonempty))))
            userMetadata.currentGroup should be (Some(Group("group3".nonempty)))
            userMetadata.availableGroups should be (UniqueList.of(Group("group3".nonempty), Group("group1".nonempty)))
            userMetadata.kibanaIndex should be (None)
            userMetadata.hiddenKibanaApps should be (Set.empty)
            userMetadata.kibanaAccess should be (None)
            userMetadata.userOrigin should be (None)
          }
        }
        "several blocks are matched and current group is set" in {
          val request = MockRequestContext.metadata.copy(
            headers = Set(basicAuthHeader("user4:pass"), header("x-ror-current-group", "group6"))
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 6
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user4".nonempty))))
            userMetadata.currentGroup should be (Some(Group("group6".nonempty)))
            userMetadata.availableGroups should be (UniqueList.of(Group("group5".nonempty), Group("group6".nonempty)))
            userMetadata.kibanaIndex should be (Some(IndexName("user4_group6_kibana_index".nonempty)))
            userMetadata.hiddenKibanaApps should be (Set.empty)
            userMetadata.kibanaAccess should be (None)
            userMetadata.userOrigin should be (None)
          }
        }
        "at least one block is matched" in {
          val request = MockRequestContext.metadata.copy(headers = Set(basicAuthHeader("user2:pass")))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 6
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user2".nonempty))))
            userMetadata.currentGroup should be (Some(Group("group2".nonempty)))
            userMetadata.availableGroups should be (UniqueList.of(Group("group2".nonempty)))
            userMetadata.kibanaIndex should be (Some(IndexName("user2_kibana_index".nonempty)))
            userMetadata.hiddenKibanaApps should be (Set(KibanaApp("user2_app1".nonempty), KibanaApp("user2_app2".nonempty)))
            userMetadata.kibanaAccess should be (Some(KibanaAccess.RO))
            userMetadata.userOrigin should be (None)
          }
        }
        "block with no available groups collected is matched" in {
          val request = MockRequestContext.metadata.copy(headers = Set(basicAuthHeader("user3:pass")))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 6
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user3".nonempty))))
            userMetadata.currentGroup should be (None)
            userMetadata.availableGroups should be (UniqueList.empty)
            userMetadata.kibanaIndex should be (Some(IndexName("user3_kibana_index".nonempty)))
            userMetadata.hiddenKibanaApps should be (Set(KibanaApp("user3_app1".nonempty), KibanaApp("user3_app2".nonempty)))
            userMetadata.kibanaAccess should be (None)
            userMetadata.userOrigin should be (None)
          }
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val request = MockRequestContext.metadata.copy(headers = Set(basicAuthHeader("userXXX:pass")))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 6
          inside(result.result) { case Forbidden => }
        }
        "current group is set but it doesn't exist on available groups list" in {
          val request = MockRequestContext.metadata.copy(
            headers = Set(basicAuthHeader("user4:pass"), header("x-ror-current-group", "group7"))
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 6
          inside(result.result) { case Forbidden => }
        }
        "block with no available groups collected is matched and current group is set" in {
          val request = MockRequestContext.metadata.copy(
            headers = Set(basicAuthHeader("user3:pass"), header("x-ror-current-group", "group7"))
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 6
          inside(result.result) { case Forbidden => }
        }
      }
    }
  }
}
