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
import io.jsonwebtoken.security.Keys
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult.Allow
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.misc.JwtUtils.*

class CurrentGroupHandlingAccessControlTests
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with Inside
    with Matchers {

  private val kbn1SignatureKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
  private val jwt1SignatureKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"

  override protected def settingsYaml: String =
    s"""
       |readonlyrest:
       |
       |  access_control_rules:
       |  - name: "User 1 block"
       |    users: ["user1"]
       |    groups: ["group2", "group3"]
       |
       |  - name: "User 2 block"
       |    users: ["user2"]
       |    groups: ["group2", "group3"]
       |
       |  - name: "User 3 block"
       |    users: ["user3"]
       |    groups: ["group2", "group3"]
       |
       |  - name: "User 4 block"
       |    users: ["user4"]
       |    ror_kbn_auth:
       |      name: "kbn1"
       |      groups: ["group2", "group3"]
       |
       |  - name: "User 5 block"
       |    users: ["user5"]
       |    groups: ["group2", "group3"]
       |
       |  - name: "User 6 block"
       |    users: ["user6"]
       |    groups: ["group2", "group3"]
       |
       |  - name: "User 7 block"
       |    users: ["user7"]
       |    jwt_auth:
       |      name: "jwt1"
       |      groups: ["group2", "group3"]
       |
       |  users:
       |  - username: user1
       |    groups: ["group1", "group2", "group3"]
       |    auth_key: "user1:pass"
       |
       |  - username: user2
       |    groups:
       |      - group1: [kbn_group1]
       |      - group2: [kbn_group2]
       |      - group3: [kbn_group2]
       |    ror_kbn_auth:
       |      name: "kbn1"
       |      groups: ["kbn_group1", "kbn_group2"]
       |
       |  - username: user3
       |    groups: ["group1", "group2", "group3"]
       |    ror_kbn_auth:
       |      name: "kbn1"
       |      groups: ["group1", "group2"]
       |
       |  - username: user5
       |    groups:
       |      - group1: [jwt_group1]
       |      - group2: [jwt_group2]
       |      - group3: [jwt_group2]
       |    jwt_auth:
       |      name: "jwt1"
       |      groups: ["jwt_group1", "jwt_group2"]
       |
       |  - username: user6
       |    groups: ["group1", "group2", "group3"]
       |    jwt_auth:
       |      name: "jwt1"
       |      groups: ["group1", "group2"]
       |
       |  ror_kbn:
       |  - name: kbn1
       |    signature_key: "$kbn1SignatureKey"
       |
       |  jwt:
       |  - name: jwt1
       |    signature_key: "$jwt1SignatureKey"
       |    user_claim: "user"
       |    groups_claim: "groups"
       |
    """.stripMargin

  "An ACL" should {
    "handle properly login request and change tenancy request" when {
      "groups rule with auth_key is used" in {
        def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
          metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: GroupId("group3") :: Nil)

          val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
          group2Metadata.block.name should be(Block.Name("User 1 block"))
          group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user1")))
          group2Metadata.userOrigin should be(None)
          group2Metadata.kibanaMetadata should be(None)

          val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
          group3Metadata.block.name should be(Block.Name("User 1 block"))
          group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user1")))
          group3Metadata.userOrigin should be(None)
          group3Metadata.kibanaMetadata should be(None)
        }

        val loginRequest = MockRequestContext.metadata.withHeaders(basicAuthHeader("user1:pass"))
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }

        val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
          basicAuthHeader("user1:pass"), currentGroupHeader("group3")
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }
      }
      "groups rule with ror_kbn_auth is used (with local groups mapping)" in {
        def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
          metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: GroupId("group3") :: Nil)

          val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
          group2Metadata.block.name should be(Block.Name("User 2 block"))
          group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user2")))
          group2Metadata.userOrigin should be(None)
          group2Metadata.kibanaMetadata should be(None)

          val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
          group3Metadata.block.name should be(Block.Name("User 2 block"))
          group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user2")))
          group3Metadata.userOrigin should be(None)
          group3Metadata.kibanaMetadata should be(None)
        }

        val jwt = Jwt(Keys.hmacShaKeyFor(kbn1SignatureKey.getBytes),
          claims = List(
          "user" := "user2",
          "groups" := List("kbn_group1", "kbn_group2", "kbn_group3")
        ))
        val loginRequest = MockRequestContext.metadata.withHeaders(bearerHeader(jwt))
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }

        val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
          bearerHeader(jwt), currentGroupHeader("group3")
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }
      }
      "groups rule with ror_kbn_auth is used (without local groups mapping)" in {
        def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
          metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: GroupId("group3") :: Nil)

          val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
          group2Metadata.block.name should be(Block.Name("User 3 block"))
          group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user3")))
          group2Metadata.userOrigin should be(None)
          group2Metadata.kibanaMetadata should be(None)

          val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
          group3Metadata.block.name should be(Block.Name("User 3 block"))
          group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user3")))
          group3Metadata.userOrigin should be(None)
          group3Metadata.kibanaMetadata should be(None)
        }

        val jwt = Jwt(Keys.hmacShaKeyFor(kbn1SignatureKey.getBytes),
          claims = List(
            "user" := "user3",
            "groups" := List("group1", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.withHeaders(bearerHeader(jwt))
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }

        val switchTenancyRequest = MockRequestContext.metadata
          .withHeaders(bearerHeader(jwt), currentGroupHeader("group3")
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }
      }
      "ror_kbn_auth is used" in {
        def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
          metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: GroupId("group3") :: Nil)

          val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
          group2Metadata.block.name should be(Block.Name("User 4 block"))
          group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user4")))
          group2Metadata.userOrigin should be(None)
          group2Metadata.kibanaMetadata should be(None)

          val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
          group3Metadata.block.name should be(Block.Name("User 4 block"))
          group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user4")))
          group3Metadata.userOrigin should be(None)
          group3Metadata.kibanaMetadata should be(None)
        }

        val jwt = Jwt(Keys.hmacShaKeyFor(kbn1SignatureKey.getBytes),
          claims = List(
            "user" := "user4",
            "groups" := List("group1", "group2", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.withHeaders(bearerHeader(jwt))
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }

        val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
          bearerHeader(jwt), currentGroupHeader("group3")
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }
      }
      "groups rule with jwt_auth is used (with local groups mapping)" in {
        def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
          metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: GroupId("group3") :: Nil)

          val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
          group2Metadata.block.name should be(Block.Name("User 5 block"))
          group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user5")))
          group2Metadata.userOrigin should be(None)
          group2Metadata.kibanaMetadata should be(None)

          val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
          group3Metadata.block.name should be(Block.Name("User 5 block"))
          group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user5")))
          group3Metadata.userOrigin should be(None)
          group3Metadata.kibanaMetadata should be(None)
        }

        val jwt = Jwt(Keys.hmacShaKeyFor(jwt1SignatureKey.getBytes),
          claims = List(
            "user" := "user5",
            "groups" := List("jwt_group1", "jwt_group2", "jwt_group3")
          ))
        val loginRequest = MockRequestContext.metadata.withHeaders(bearerHeader(jwt))
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }

        val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
          bearerHeader(jwt), currentGroupHeader("group3")
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }
      }
      "groups rule with jwt_auth is used (without local groups mapping)" in {
        def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
          metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: GroupId("group3") :: Nil)

          val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
          group2Metadata.block.name should be(Block.Name("User 6 block"))
          group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user6")))
          group2Metadata.userOrigin should be(None)
          group2Metadata.kibanaMetadata should be(None)

          val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
          group3Metadata.block.name should be(Block.Name("User 6 block"))
          group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user6")))
          group3Metadata.userOrigin should be(None)
          group3Metadata.kibanaMetadata should be(None)
        }

        val jwt = Jwt(Keys.hmacShaKeyFor(jwt1SignatureKey.getBytes),
          claims = List(
            "user" := "user6",
            "groups" := List("group1", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.withHeaders(bearerHeader(jwt))
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }

        val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
          bearerHeader(jwt), currentGroupHeader("group3")
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }
      }
      "jwt_auth is used" in {
        def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
          metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: GroupId("group3") :: Nil)

          val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
          group2Metadata.block.name should be(Block.Name("User 7 block"))
          group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user7")))
          group2Metadata.userOrigin should be(None)
          group2Metadata.kibanaMetadata should be(None)

          val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
          group3Metadata.block.name should be(Block.Name("User 7 block"))
          group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user7")))
          group3Metadata.userOrigin should be(None)
          group3Metadata.kibanaMetadata should be(None)
        }

        val jwt = Jwt(Keys.hmacShaKeyFor(jwt1SignatureKey.getBytes),
          claims = List(
            "user" := "user7",
            "groups" := List("group1", "group2", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.withHeaders(bearerHeader(jwt))
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }

        val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
          bearerHeader(jwt), currentGroupHeader("group3")
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
          assertAllowUserMetadataWithGroupsResponse(userMetadata)
        }
      }
    }
  }
}
