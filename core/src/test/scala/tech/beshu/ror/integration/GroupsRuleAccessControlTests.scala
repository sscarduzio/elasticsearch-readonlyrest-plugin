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
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult.{Allow, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.misc.JwtUtils.*
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.util.Base64

class GroupsRuleAccessControlTests
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with Inside {

  private val (pub, secret) = Random.generateRsaRandomKeys

  override protected val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def configYaml: String =
    s"""
       |readonlyrest:
       |
       |  access_control_rules:
       |
       |  - name: "Allowed only for group3 OR group4"
       |    groups: [group3, group4]
       |    indices: ["g34_index"]
       |
       |  - name: "Allowed only for group1 OR group2"
       |    groups: [group1, group2]
       |    indices: ["g12_index"]
       |
       |  - name: "Allowed only for group1 AND group43"
       |    groups_and: [group1, group3]
       |    indices: ["g13_index"]
       |
       |  - name: "Allowed only for group5"
       |    groups: ["@explode{jwt:roles}"]
       |    indices: ["g5_index"]
       |    jwt_auth: "jwt1"
       |
       |  - name: "::ELKADMIN::"
       |    kibana:
       |      access: unrestricted
       |    groups: ["admin"]
       |
       |  - name: "ror_kbn_auth in root of ACL"
       |    users: ["user_root_ror_kbn_auth"]
       |    ror_kbn_auth:
       |      name: kbn1
       |      groups: "example_group_ror_kbn_auth"
       |
       |  - name: "local groups-based ror_kbn_auth"
       |    users: ["user_local_groups_ror_kbn_auth"]
       |    groups: "example_group_ror_kbn_auth"
       |
       |  - name: "jwt_auth in root of ACL"
       |    users: ["user_root_jwt_auth"]
       |    jwt_auth:
       |      name: jwt2
       |      groups: "example_group_jwt_auth"
       |
       |  - name: "local groups-based jwt_auth"
       |    users: ["user_local_groups_jwt_auth"]
       |    groups: "example_group_jwt_auth"
       |
       |  - name: "ldap_auth in root of ACL"
       |    headers: ["test:acl_root"]
       |    ldap_auth:
       |      name: ldap2
       |      groups: "europe"
       |
       |  - name: "local groups-based ldap_auth"
       |    headers: ["test:local_groups"]
       |    groups: "europe"
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
       |  - username: user3
       |    groups: ["group5"]
       |    jwt_auth: "jwt1"
       |
       |  - username: "*"
       |    groups: ["personal_admin", "admin", "admin_ops", "admin_dev"]
       |    ldap_auth:
       |      name: ldap1
       |      groups: ["group3"]
       |
       |  - username: "*"
       |    ror_kbn_auth:
       |      name: "kbn1"
       |      groups: ["example_group_ror_kbn_auth"]
       |    groups: ["example_group_ror_kbn_auth"]
       |
       |  - username: "*"
       |    jwt_auth:
       |      name: "jwt2"
       |      groups: ["example_group_jwt_auth"]
       |    groups: ["example_group_jwt_auth"]
       |
       |  - username: "*"
       |    ldap_auth:
       |      name: "ldap2"
       |      groups: ["europe"]
       |    groups: ["europe"]
       |
       |  proxy_auth_configs:
       |  - name: "proxy1"
       |    user_id_header: "X-Auth-Token"
       |
       |  jwt:
       |  - name: jwt1
       |    signature_algo: "RSA"
       |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |    user_claim: "userId"
       |    roles_claim: roles
       |
       |  - name: jwt2
       |    signature_algo: "RSA"
       |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |    user_claim: "user"
       |    roles_claim: "groups"
       |
       |  ror_kbn:
       |  - name: kbn1
       |    signature_algo: "RSA"
       |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |
       |  ldaps:
       |    - name: ldap1
       |      host: "${SingletonLdapContainers.ldap1.ldapHost}"
       |      port: ${SingletonLdapContainers.ldap1.ldapPort}
       |      ssl_enabled: false                                        # default true
       |      ssl_trust_all_certs: true                                 # default false
       |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |      bind_password: "password"                                 # skip for anonymous bind
       |      connection_pool_size: 10                                  # default 30
       |      connection_timeout_in_sec: 10                             # default 1
       |      request_timeout_in_sec: 10                                # default 1
       |      cache_ttl_in_sec: 60                                      # default 0 - cache disabled
       |      users:
       |        search_user_base_DN: "ou=People,dc=example,dc=com"
       |        user_id_attribute: "uid"                                # default "uid"
       |      groups:
       |        search_groups_base_DN: "ou=Groups,dc=example,dc=com"
       |        unique_member_attribute: "uniqueMember"                 # default "uniqueMember"
       |
       |    - name: ldap2
       |      host: "${SingletonLdapContainers.ldap1.ldapHost}"
       |      port: ${SingletonLdapContainers.ldap1.ldapPort}
       |      ssl_enabled: false                                        # default true
       |      ssl_trust_all_certs: true                                 # default false
       |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |      bind_password: "password"                                 # skip for anonymous bind
       |      connection_pool_size: 10                                  # default 30
       |      connection_timeout: 10s                                   # default 1
       |      request_timeout: 10s                                      # default 1
       |      cache_ttl: 60s                                            # default 0 - cache disabled
       |      users:
       |        search_user_base_DN: "ou=Gods,dc=example,dc=com"
       |      groups:
       |        mode: search_groups_in_user_entries
       |        search_groups_base_DN: "ou=Regions,dc=example,dc=com"
       |        group_id_attribute: "cn"
       |        groups_from_user_attribute: "title"
       |
    """.stripMargin

  "An ACL" when {
    "auth_key is used with local groups" should {
      "allow when authorization satisfies all groups" in {
        val request = MockRequestContext.indices
          .withHeaders(header("Authorization", "Basic " + Base64.getEncoder.encodeToString("user2:pass".getBytes)))
          .copy(filteredIndices = Set(requestedIndex("g34_index")))
        val result = acl.handleRegularRequest(request).runSyncUnsafe()
        result.history should have size 1
        inside(result.result) {
          case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user2"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(group("group3"), group("group4")))
        }
      }
    }
    "proxy auth is used together with groups" should {
      "allow to proceed" when {
        "proxy auth user is correct one" in {
          val request = MockRequestContext.indices
            .withHeaders(header("X-Auth-Token", "user1-proxy-id"))
            .copy(
              filteredIndices = Set(requestedIndex("g12_index")),
              allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
            )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 2
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user1-proxy-id"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(group("group1")))
          }
        }
      }
      "not allow to proceed" when {
        "proxy auth user is unknown" in {
          val request = MockRequestContext.indices
            .withHeaders(header("X-Auth-Token", "user1-invalid"))
            .copy(
              filteredIndices = Set(requestedIndex("g12_index")),
              allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
            )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          inside(result.result) { case ForbiddenByMismatched(_) =>
          }
        }
      }
    }
    "jwt auth is used together with groups" should {
      "allow to proceed" when {
        "at least one of user's roles is declared in groups" in {
          val jwt = Jwt(secret, claims = List(
            "userId" := "user3",
            "roles" := List("group5", "group6", "group7")
          ))
          val request = MockRequestContext.indices
            .withHeaders(bearerHeader(jwt))
            .copy(
              filteredIndices = Set(requestedIndex("g*")),
              allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
            )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user3"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(group("group5")))
          }
        }
      }
    }
    "ldap auth with groups mapping is used together with groups" should {
      "allow to proceed" when {
        "user can be authenticated and authorized (externally and locally)" in {
          val request = MockRequestContext.indices
            .withHeaders(basicAuthHeader("morgan:user1"), currentGroupHeader("admin"))
            .copy(
              filteredIndices = Set(requestedIndex(".kibana")),
              allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName(".kibana")))
            )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 5
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("morgan"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(group("admin")))
          }
        }
      }
    }
  }

  "An authentication & authorization rule placed in the ACL root or used with local groups" should {
    "produce the same responses" when {
      "it's ror_kbn_auth rule" in {
        def metadataRequest(username: String) = {
          val request = MockRequestContext.metadata.withHeaders(
            bearerHeader(Jwt(secret, claims = List(
              "user" := username,
              "groups" := List("example_group_ror_kbn_auth"),
              "x-ror-origin" := "example_origin"
            )))
          )
          acl.handleMetadataRequest(request).runSyncUnsafe()
        }

        val result1 = metadataRequest(username = "user_root_ror_kbn_auth")
        val result2 = metadataRequest(username = "user_local_groups_ror_kbn_auth")

        inside(result1.result) { case UserMetadataRequestResult.Allow(userMetadata1, matchedBlock1) =>
          inside(result2.result) { case UserMetadataRequestResult.Allow(userMetadata2, matchedBlock2) =>
            matchedBlock1.name should be (Block.Name("ror_kbn_auth in root of ACL"))
            matchedBlock2.name should be (Block.Name("local groups-based ror_kbn_auth"))

            userMetadata1.currentGroupId should be(userMetadata2.currentGroupId)
            userMetadata1.kibanaIndex should be(userMetadata2.kibanaIndex)
            userMetadata1.hiddenKibanaApps should be(userMetadata2.hiddenKibanaApps)
            userMetadata1.allowedKibanaApiPaths should be(userMetadata2.allowedKibanaApiPaths)
            userMetadata1.kibanaAccess should be(userMetadata2.kibanaAccess)
            userMetadata1.userOrigin should be(userMetadata2.userOrigin)
            userMetadata1.jwtToken.isDefined should be(userMetadata2.jwtToken.isDefined)
          }
        }
      }
      "it's jwt_auth rule" in {
        def metadataRequest(username: String) = {
          val request = MockRequestContext.metadata.withHeaders(
            bearerHeader(Jwt(secret, claims = List(
              "user" := username,
              "groups" := List("example_group_jwt_auth"),
              "x-ror-origin" := "example_origin"
            )))
          )
          acl.handleMetadataRequest(request).runSyncUnsafe()
        }

        val result1 = metadataRequest(username = "user_root_jwt_auth")
        val result2 = metadataRequest(username = "user_local_groups_jwt_auth")

        inside(result1.result) { case UserMetadataRequestResult.Allow(userMetadata1, matchedBlock1) =>
          inside(result2.result) { case UserMetadataRequestResult.Allow(userMetadata2, matchedBlock2) =>
            matchedBlock1.name should be (Block.Name("jwt_auth in root of ACL"))
            matchedBlock2.name should be (Block.Name("local groups-based jwt_auth"))

            userMetadata1.currentGroupId should be(userMetadata2.currentGroupId)
            userMetadata1.kibanaIndex should be(userMetadata2.kibanaIndex)
            userMetadata1.hiddenKibanaApps should be(userMetadata2.hiddenKibanaApps)
            userMetadata1.allowedKibanaApiPaths should be(userMetadata2.allowedKibanaApiPaths)
            userMetadata1.kibanaAccess should be(userMetadata2.kibanaAccess)
            userMetadata1.userOrigin should be(userMetadata2.userOrigin)
            userMetadata1.jwtToken.isDefined should be(userMetadata2.jwtToken.isDefined)
          }
        }
      }
      "it's ldap_auth rule" in {
        def metadataRequest(headerValue: String) = {
          val request = MockRequestContext.metadata.withHeaders(
            basicAuthHeader(s"jesus:user1"),
            header("test", headerValue)
          )
          acl.handleMetadataRequest(request).runSyncUnsafe()
        }

        val result1 = metadataRequest(headerValue = "acl_root")
        val result2 = metadataRequest(headerValue = "local_groups")

        inside(result1.result) { case UserMetadataRequestResult.Allow(userMetadata1, matchedBlock1) =>
          inside(result2.result) { case UserMetadataRequestResult.Allow(userMetadata2, matchedBlock2) =>
            matchedBlock1.name should be (Block.Name("ldap_auth in root of ACL"))
            matchedBlock2.name should be (Block.Name("local groups-based ldap_auth"))

            userMetadata1.currentGroupId should be(userMetadata2.currentGroupId)
            userMetadata1.kibanaIndex should be(userMetadata2.kibanaIndex)
            userMetadata1.hiddenKibanaApps should be(userMetadata2.hiddenKibanaApps)
            userMetadata1.allowedKibanaApiPaths should be(userMetadata2.allowedKibanaApiPaths)
            userMetadata1.kibanaAccess should be(userMetadata2.kibanaAccess)
            userMetadata1.userOrigin should be(userMetadata2.userOrigin)
            userMetadata1.jwtToken.isDefined should be(userMetadata2.jwtToken.isDefined)
          }
        }
      }
    }
  }

  private def allIndicesAndAliasesInTheTestCase() = Set(
    fullLocalIndexWithAliases(fullIndexName("g12_index")),
    fullLocalIndexWithAliases(fullIndexName("g13_index")),
    fullLocalIndexWithAliases(fullIndexName("g34_index")),
    fullLocalIndexWithAliases(fullIndexName("g5_index"))
  )
}
