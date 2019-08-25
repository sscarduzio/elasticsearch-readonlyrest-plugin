package tech.beshu.ror.integration

import cats.implicits._
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult._
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Group, IndexName, KibanaApp, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.accesscontrol.orders.groupOrder
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.utils.TestsUtils._

import scala.collection.SortedSet

class CurrentUserMetadataAccessControlTests extends WordSpec with BaseYamlLoadedAccessControlTest with MockFactory with Inside {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "User 1 - index1"
      |    indices: ["index_user1_1"]
      |    groups: [group2, group3]
      |
      |  - name: "User 1 - index2"
      |    indices: ["index_user1_2"]
      |    groups: [group2, group1]
      |
      |  - name: "User 2"
      |    indices: ["index_user2"]
      |    groups: [group2, group3]
      |    kibana_index: "user2_kibana_index"
      |    kibana_hide_apps: ["user2_app1", "user2_app2"]
      |
      |  - name: "User 3"
      |    auth_key: "user3:pass"
      |    indices: ["index_user3"]
      |    kibana_index: "user3_kibana_index"
      |    kibana_hide_apps: ["user3_app1", "user3_app2"]
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
    """.stripMargin

  "An ACL" when {
    "handling current user metadata kibana plugin request" should {
      "allow to proceed" when {
        "several blocks are matched" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user1:pass")),
            indices = Set(IndexName("index_user1_*".nonempty)),
            involvesIndices = true
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case Allow(userMetadata) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user1".nonempty))))
            userMetadata.currentGroup should be (Some(Group("group1".nonempty)))
            userMetadata.availableGroups should be (SortedSet(Group("group1".nonempty), Group("group3".nonempty)))
            userMetadata.foundKibanaIndex should be (None)
            userMetadata.hiddenKibanaApps should be (Set.empty)
          }
        }
        "at least one block is matched" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user2:pass")),
            indices = Set(IndexName("index_user2".nonempty)),
            involvesIndices = true
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case Allow(userMetadata) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user2".nonempty))))
            userMetadata.currentGroup should be (Some(Group("group2".nonempty)))
            userMetadata.availableGroups should be (SortedSet(Group("group2".nonempty), Group("group4".nonempty)))
            userMetadata.foundKibanaIndex should be (Some(IndexName("user2_kibana_index".nonempty)))
            userMetadata.hiddenKibanaApps should be (Set(KibanaApp("user2_app1".nonempty), KibanaApp("user2_app2".nonempty)))
          }
        }
        "block with no available groups collected is matched" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user3:pass")),
            indices = Set(IndexName("index_user3".nonempty)),
            involvesIndices = true
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case Allow(userMetadata) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user3".nonempty))))
            userMetadata.currentGroup should be (None)
            userMetadata.availableGroups should be (Set.empty)
            userMetadata.foundKibanaIndex should be (Some(IndexName("user3_kibana_index".nonempty)))
            userMetadata.hiddenKibanaApps should be (Set(KibanaApp("user3_app1".nonempty), KibanaApp("user3_app2".nonempty)))
          }
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user4:pass")),
            involvesIndices = true
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case Forbidden => }
        }
      }
    }
  }
}
