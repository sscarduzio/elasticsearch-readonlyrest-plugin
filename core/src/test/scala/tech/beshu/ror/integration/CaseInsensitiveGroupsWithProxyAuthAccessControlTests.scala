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
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.Allow
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueList

class CaseInsensitiveGroupsWithProxyAuthAccessControlTests extends AnyWordSpec
  with BaseYamlLoadedAccessControlTest with Inside {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |  username_case_sensitivity: case_insensitive
      |
      |  access_control_rules:
      |  - name: "Allowed only for group1 and group2"
      |    groups: [group1]
      |    indices: ["g12_index"]
      |
      |  users:
      |  - username: user1-proxy-iD
      |    groups: ["group1"]
      |    proxy_auth:
      |      proxy_auth_config: "proxy1"
      |      users: ["User1-proxy-iD"]
      |
      |  proxy_auth_configs:
      |
      |  - name: "proxy1"
      |    user_id_header: "X-Auth-Token"
    """.stripMargin

  "An ACL" when {
    "user are case insensitive" should {
      "allow to proceed" when {
        "user is user1" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "user1-proxy-id")),
            filteredIndices = Set(clusterIndexName("g12_index")),
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("g12_index")),
              fullLocalIndexWithAliases(fullIndexName("g34_index"))
            )
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user1-proxy-id"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(GroupName("group1")))
          }
        }
        "user is User1" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "User1-proxy-id")),
            filteredIndices = Set(clusterIndexName("g12_index")),
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("g12_index")),
              fullLocalIndexWithAliases(fullIndexName("g34_index"))
            )
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("User1-proxy-id"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(GroupName("group1")))
          }
        }
      }
    }
  }
}
