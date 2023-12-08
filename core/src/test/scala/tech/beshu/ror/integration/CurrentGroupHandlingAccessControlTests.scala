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
import io.jsonwebtoken.security.Keys
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult.Allow
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.{basicAuthHeader, bearerHeader, currentGroupHeader, groupFromId}
import tech.beshu.ror.utils.misc.JwtUtils._
import tech.beshu.ror.utils.uniquelist.UniqueList

class CurrentGroupHandlingAccessControlTests
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with Inside
    with Matchers {

  private val kbn1SignatureKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
  private val jwt1SignatureKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"

  override protected def configYaml: String =
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
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(basicAuthHeader("user1:pass"))
        )
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user1"))))
          userMetadata.currentGroupId should be (Some(GroupId("group2")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }

        val switchTenancyRequest = MockRequestContext.metadata.copy(
          headers = Set(basicAuthHeader("user1:pass"), currentGroupHeader("group3"))
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user1"))))
          userMetadata.currentGroupId should be (Some(GroupId("group3")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }
      }
      "groups rule with ror_kbn_auth is used (with local groups mapping)" in {
        val jwt = Jwt(Keys.hmacShaKeyFor(kbn1SignatureKey.getBytes),
          claims = List(
          "user" := "user2",
          "groups" := List("kbn_group1", "kbn_group2", "kbn_group3")
        ))
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt))
        )
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user2"))))
          userMetadata.currentGroupId should be (Some(GroupId("group2")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }

        val switchTenancyRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt), currentGroupHeader("group3"))
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user2"))))
          userMetadata.currentGroupId should be (Some(GroupId("group3")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }
      }
      "groups rule with ror_kbn_auth is used (without local groups mapping)" in {
        val jwt = Jwt(Keys.hmacShaKeyFor(kbn1SignatureKey.getBytes),
          claims = List(
            "user" := "user3",
            "groups" := List("group1", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt))
        )
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user3"))))
          userMetadata.currentGroupId should be (Some(GroupId("group2")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }

        val switchTenancyRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt), currentGroupHeader("group3"))
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user3"))))
          userMetadata.currentGroupId should be (Some(GroupId("group3")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }
      }
      "ror_kbn_auth is used" in {
        val jwt = Jwt(Keys.hmacShaKeyFor(kbn1SignatureKey.getBytes),
          claims = List(
            "user" := "user4",
            "groups" := List("group1", "group2", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt))
        )
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user4"))))
          userMetadata.currentGroupId should be (Some(GroupId("group2")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }

        val switchTenancyRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt), currentGroupHeader("group3"))
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user4"))))
          userMetadata.currentGroupId should be (Some(GroupId("group3")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }
      }
      "groups rule with jwt_auth is used (with local groups mapping)" in {
        val jwt = Jwt(Keys.hmacShaKeyFor(jwt1SignatureKey.getBytes),
          claims = List(
            "user" := "user5",
            "groups" := List("jwt_group1", "jwt_group2", "jwt_group3")
          ))
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt))
        )
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user5"))))
          userMetadata.currentGroupId should be (Some(GroupId("group2")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }

        val switchTenancyRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt), currentGroupHeader("group3"))
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user5"))))
          userMetadata.currentGroupId should be (Some(GroupId("group3")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }
      }
      "groups rule with jwt_auth is used (without local groups mapping)" in {
        val jwt = Jwt(Keys.hmacShaKeyFor(jwt1SignatureKey.getBytes),
          claims = List(
            "user" := "user6",
            "groups" := List("group1", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt))
        )
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user6"))))
          userMetadata.currentGroupId should be (Some(GroupId("group2")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }

        val switchTenancyRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt), currentGroupHeader("group3"))
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user6"))))
          userMetadata.currentGroupId should be (Some(GroupId("group3")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }
      }
      "jwt_auth is used" in {
        val jwt = Jwt(Keys.hmacShaKeyFor(jwt1SignatureKey.getBytes),
          claims = List(
            "user" := "user7",
            "groups" := List("group1", "group2", "group3")
          ))
        val loginRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt))
        )
        val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
        inside(loginResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user7"))))
          userMetadata.currentGroupId should be (Some(GroupId("group2")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }

        val switchTenancyRequest = MockRequestContext.metadata.copy(
          headers = Set(bearerHeader(jwt), currentGroupHeader("group3"))
        )
        val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
        inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
          userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user7"))))
          userMetadata.currentGroupId should be (Some(GroupId("group3")))
          userMetadata.availableGroups should be (UniqueList.of(groupFromId("group2"), groupFromId("group3")))
        }
      }
    }
  }
}
