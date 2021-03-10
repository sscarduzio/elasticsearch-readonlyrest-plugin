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

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inside
import tech.beshu.ror.accesscontrol.domain.{Group, IndexName, IndexWithAliases, User}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.uniquelist.UniqueList
import tech.beshu.ror.utils.TestsUtils._
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched.Cause
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.{Allow, ForbiddenByMismatched}

class GroupsWithProxyAuthAccessControlTests extends AnyWordSpec with BaseYamlLoadedAccessControlTest with Inside {
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
    """.stripMargin

  "An ACL" when {
    "proxy auth is used together with groups" should {
      "allow to proceed" when {
        "proxy auth user is correct one" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "user1-proxy-id")),
            filteredIndices = Set(IndexName("g12_index")),
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("g12_index"), Set.empty),
              IndexWithAliases(IndexName("g34_index"), Set.empty)
            )
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 2
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user1-proxy-id".nonempty))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(Group("group1".nonempty)))
          }
        }
      }
      "not allow to proceed" when {
        "proxy auth user is unknown" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "user1-invalid")),
            filteredIndices = Set(IndexName("g12_index")),
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("g12_index"), Set.empty),
              IndexWithAliases(IndexName("g34_index"), Set.empty)
            )
          )
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
