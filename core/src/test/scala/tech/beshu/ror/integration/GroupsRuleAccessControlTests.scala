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
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.{Allow, IndexNotFound}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.misc.JwtUtils._
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
      |    kibana_access: unrestricted
      |    groups: ["admin"]
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
      |  proxy_auth_configs:
      |
      |  - name: "proxy1"
      |    user_id_header: "X-Auth-Token"
      |
      |  jwt:
      |
      |  - name: jwt1
      |    signature_algo: "RSA"
      |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
      |    user_claim: "userId"
      |    roles_claim: roles
      |
      |  ldaps:
      |    - name: ldap1
      |      host: "${SingletonLdapContainers.ldap1.ldapHost}"
      |      port: ${SingletonLdapContainers.ldap1.ldapPort}
      |      ssl_enabled: false                                        # default true
      |      ssl_trust_all_certs: true                                 # default false
      |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
      |      bind_password: "password"                                 # skip for anonymous bind
      |      search_user_base_DN: "ou=People,dc=example,dc=com"
      |      search_groups_base_DN: "ou=Groups,dc=example,dc=com"
      |      user_id_attribute: "uid"                                  # default "uid"
      |      unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
      |      connection_pool_size: 10                                  # default 30
      |      connection_timeout_in_sec: 10                             # default 1
      |      request_timeout_in_sec: 10                                # default 1
      |      cache_ttl_in_sec: 60                                      # default 0 - cache disabled
      |
    """.stripMargin

  "An ACL" when {
    "auth_key is used with local groups" should {
      "allow when authorization satisfies all groups" in {
        val request = MockRequestContext.indices.copy(
          headers = Set(header("Authorization", "Basic " + Base64.getEncoder.encodeToString("user2:pass".getBytes))),
          filteredIndices = Set(clusterIndexName("g34_index")),
        )
        val result = acl.handleRegularRequest(request).runSyncUnsafe()
        result.history should have size 1
        inside (result.result) {
          case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user2"))))
            blockContext.userMetadata.availableGroups should be (UniqueList.of(Group("group3"), Group("group4")))
        }
      }
    }
    "proxy auth is used together with groups" should {
      "allow to proceed" when {
        "proxy auth user is correct one" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "user1-proxy-id")),
            filteredIndices = Set(clusterIndexName("g12_index")),
            allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 2
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user1-proxy-id"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(Group("group1")))
          }
        }
      }
      "not allow to proceed" when {
        "proxy auth user is unknown" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(header("X-Auth-Token", "user1-invalid")),
            filteredIndices = Set(clusterIndexName("g12_index")),
            allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 5
          inside(result.result) { case IndexNotFound() =>
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
          val request = MockRequestContext.indices.copy(
            headers = Set(bearerHeader(jwt)),
            filteredIndices = Set(clusterIndexName("g*")),
            allIndicesAndAliases = allIndicesAndAliasesInTheTestCase()
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 4
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user3"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(Group("group5")))
          }
        }
      }
    }
    "ldap auth with groups mapping is used together with groups" should {
      "allow to proceed" when {
        "user can be authenticated and authorized (externally and locally)" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(
              basicAuthHeader("morgan:user1"),
              currentGroupHeader( "admin")
            ),
            filteredIndices = Set(clusterIndexName(".kibana")),
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName(".kibana")))
          )
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 5
          inside(result.result) { case Allow(blockContext, _) =>
            blockContext.userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("morgan"))))
            blockContext.userMetadata.availableGroups should be(UniqueList.of(Group("admin")))
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
