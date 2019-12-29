package tech.beshu.ror.integration

import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.uniquelist.UniqueList
import tech.beshu.ror.utils.TestsUtils._
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched.Cause
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.{Allow, ForbiddenByMismatched}

class GroupsWithProxyAuthAccessControlTests extends WordSpec with BaseYamlLoadedAccessControlTest with Inside {
  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Allowed only for group3 and group4"
      |    groups: [group3, group4]
      |    indices: ["g34_index"]
      |
      |  - name: "Allowed only for group1 and group2"
      |    groups: [group1, group2]
      |    indices: ["g12_index"]
      |
      |  users:
      |
      |  - username: user1-proxy-id
      |    groups: ["group1"]
      |    proxy_auth:
      |      proxy_auth_config: "proxy1"
      |      users: ["user1-proxy-id"]
      |
      |  - username: user2
      |    groups: ["group3", "group4"]
      |    auth_key: "user2:pass"
      |
      |  proxy_auth_configs:
      |
      |  - name: "proxy1"
      |    user_id_header: "X-Auth-Token"
      |
    """.stripMargin

  "An ACL" when {
    "proxy auth is used together with groups" should {
      "allow to proceed" when {
        "proxy auth user is correct one" in {
          val request = MockRequestContext.default.copy(headers = Set(header("X-Auth-Token", "user1-proxy-id")))
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 2
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user1-proxy-id".nonempty))))
            blockContext.availableGroups should be(UniqueList.of(Group("group1".nonempty)))
          }
        }
      }
      "not allow to proceed" when {
        "proxy auth user is unknown" in {
          val request = MockRequestContext.default.copy(headers = Set(header("X-Auth-Token", "user1-invalid")))
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 2
          inside(result.result) { case ForbiddenByMismatched(causes) =>
            causes.toNonEmptyList.toList should have size 1
            causes.toNonEmptyList.head should be (Cause.OperationNotAllowed)
          }
        }
      }
    }
  }
}
