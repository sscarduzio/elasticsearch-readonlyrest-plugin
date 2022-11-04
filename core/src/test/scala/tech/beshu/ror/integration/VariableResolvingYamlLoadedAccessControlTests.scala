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

import com.dimafeng.testcontainers.ForAllTestContainer
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableRequestBlockContext, GeneralIndexRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.containers.NonStoppableLdapContainer
import tech.beshu.ror.utils.misc.JwtUtils._
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.util.Base64

class VariableResolvingYamlLoadedAccessControlTests extends AnyWordSpec
  with BaseYamlLoadedAccessControlTest
  with ForAllTestContainer
  with MockFactory with Inside {

  override val container: NonStoppableLdapContainer = SingletonLdapContainers.ldap1

  override protected val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  private lazy val (pub, secret) = Random.generateRsaRandomKeys

  override protected def configYaml: String =
    s"""
       |readonlyrest:
       |
       |  enable: $${READONLYREST_ENABLE}
       |
       |  access_control_rules:
       |   - name: "CONTAINER ADMIN"
       |     type: allow
       |     auth_key: admin:container
       |
       |   - name: "Group name from header variable"
       |     type: allow
       |     groups: ["g4", "@{X-my-group-name-1}", "@{header:X-my-group-name-2}" ]
       |
       |   - name: "Group name from env variable"
       |     type: allow
       |     groups: ["g@{env:sys_group_1}"]
       |
       |   - name: "Group name from env variable (old syntax)"
       |     type: allow
       |     groups: ["g$${sys_group_2}"]
       |
       |   - name: "Variables usage in filter"
       |     type: allow
       |     filter: '{"bool": { "must": { "terms": { "user_id": [@{jwt:user_id_list}] }}}}'
       |     jwt_auth: "jwt3"
       |     users: ["user5"]
       |
       |   - name: "Group name from jwt variable (array)"
       |     type: allow
       |     jwt_auth:
       |       name: "jwt1"
       |     indices: ["g@explode{jwt:tech.beshu.mainGroup}"]
       |
       |   - name: "Group name from jwt variable"
       |     type: allow
       |     jwt_auth:
       |       name: "jwt2"
       |     indices: ["g@explode{jwt:tech.beshu.mainGroupsString}"]
       |
       |   - name: "LDAP groups explode"
       |     type: allow
       |     groups: ["g1", "g2", "g3"]
       |     indices: ["test-@explode{acl:available_groups}"]
       |     filter: '{"bool": { "must": { "terms": { "group_id": [@{acl:available_groups}] }}}}'
       |
       |  users:
       |   - username: user1
       |     groups: ["g1", "g2", "g3", "gs1"]
       |     auth_key: $${USER1_PASS}
       |
       |   - username: user2
       |     groups: ["g1", "g2", "g3", "gs2"]
       |     auth_key: user2:passwd
       |
       |   - username: "*"
       |     groups:
       |       - g1: group1
       |       - g2: [group2]
       |       - g3: "group3"
       |     ldap_auth:
       |       name: "ldap1"
       |       groups: ["group1", "group2", "group3"]
       |
       |  jwt:
       |
       |   - name: jwt1
       |     signature_algo: "RSA"
       |     signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |     user_claim: "userId"
       |     groups_claim: "tech.beshu.mainGroup"
       |
       |   - name: jwt2
       |     signature_algo: "RSA"
       |     signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |     user_claim: "userId"
       |     groups_claim: "tech.beshu.mainGroupsString"
       |
       |   - name: jwt3
       |     signature_algo: "RSA"
       |     signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |     user_claim: "userId"
       |     groups_claim: "tech.beshu.mainGroupsString"
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
    """.stripMargin

  "An ACL" when {
    "is configured using config above" should {
      "allow to proceed" when {
        "old style header variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-name-1", "g3"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupName("g3")),
              availableGroups = UniqueList.of(GroupName("g3"))
            ) {
              blockContext
            }
          }
        }
        "new style header variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-name-2", "g3"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupName("g3")),
              availableGroups = UniqueList.of(GroupName("g3"))
            ) {
              blockContext
            }
          }
        }
        "old style of env variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user2:passwd"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 4
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from env variable (old syntax)"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
              currentGroup = Some(GroupName("gs2")),
              availableGroups = UniqueList.of(GroupName("gs2"))
            ) {
              blockContext
            }
          }
        }
        "new style of env variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 3
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from env variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupName("gs1")),
              availableGroups = UniqueList.of(GroupName("gs1"))
            ) {
              blockContext
            }
          }
        }
        "JWT variable is used (array)" in {
          val jwt = Jwt(secret, claims = List(
            "userId" := "user3",
            "tech" :-> "beshu" :-> "mainGroup" := List("j1", "j2")
          ))
          val request = MockRequestContext.indices.copy(
            headers = Set(bearerHeader(jwt)),
            filteredIndices = Set(clusterIndexName("gj1"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 6
          inside(result.result) { case RegularRequestResult.Allow(blockContext: GeneralIndexRequestBlockContext, block) =>
            block.name should be(Block.Name("Group name from jwt variable (array)"))
            blockContext.userMetadata should be(
              UserMetadata
                .empty
                .withLoggedUser(DirectlyLoggedUser(User.Id("user3")))
                .withJwtToken(JwtTokenPayload(jwt.defaultClaims()))
            )
            blockContext.filteredIndices should be(Set(clusterIndexName("gj1")))
            blockContext.responseHeaders should be(Set.empty)
          }
        }
        "JWT variable is used (CSV string)" in {
          val jwt = Jwt(secret, claims = List(
            "userId" := "user4",
            "tech" :-> "beshu" :-> "mainGroupsString" := "j0,j3"
          ))

          val request = MockRequestContext.indices.copy(
            headers = Set(bearerHeader(jwt)),
            filteredIndices = Set(clusterIndexName("gj0")),
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("gj0")))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 7
          inside(result.result) { case RegularRequestResult.Allow(blockContext: GeneralIndexRequestBlockContext, block) =>
            block.name should be(Block.Name("Group name from jwt variable"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("user4")))
                .withJwtToken(JwtTokenPayload(jwt.defaultClaims()))
            )
            blockContext.filteredIndices should be(Set(clusterIndexName("gj0")))
            blockContext.responseHeaders should be(Set.empty)
          }
        }
        "JWT variable in filter query is used" in {
          val jwt = Jwt(secret, claims = List(
            "userId" := "user5",
            "user_id_list" := List("alice", "bob")
          ))

          val request = MockRequestContext.search.copy(
            headers = Set(bearerHeader(jwt)),
            indices = Set.empty,
            allIndicesAndAliases = Set.empty
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 5
          inside(result.result) { case RegularRequestResult.Allow(blockContext: FilterableRequestBlockContext, block) =>
            block.name should be(Block.Name("Variables usage in filter"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("user5")))
                .withJwtToken(JwtTokenPayload(jwt.defaultClaims()))
            )
            blockContext.filteredIndices should be(Set.empty)
            blockContext.responseHeaders should be(Set.empty)
            blockContext.filter should be(Some(Filter("""{"bool": { "must": { "terms": { "user_id": ["alice","bob"] }}}}""")))
          }
        }
        "Available groups env is used" in {
          val request = MockRequestContext.search.copy(
            headers = Set(basicAuthHeader("cartman:user2")),
            indices = Set(clusterIndexName("*")),
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-g1")),
              fullLocalIndexWithAliases(fullIndexName("test-g2")),
              fullLocalIndexWithAliases(fullIndexName("test-g3"))
            )
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 8
          inside(result.result) { case RegularRequestResult.Allow(blockContext: FilterableRequestBlockContext, block) =>
            block.name should be(Block.Name("LDAP groups explode"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("cartman")))
                .withCurrentGroup(GroupName("g1"))
                .withAvailableGroups(UniqueList.of(GroupName("g1"), GroupName("g3")))
            )
            blockContext.filteredIndices should be(Set(clusterIndexName("test-g1"), clusterIndexName("test-g3")))
            blockContext.responseHeaders should be(Set.empty)
            blockContext.filter should be (Some(Filter("""{"bool": { "must": { "terms": { "group_id": ["g1","g3"] }}}}""")))
          }
        }
      }
    }
  }

  override implicit protected def envVarsProvider: EnvVarsProvider = {
    case EnvVarName(n) if n.value == "sys_group_1" => Some("s1")
    case EnvVarName(n) if n.value == "sys_group_2" => Some("s2")
    case EnvVarName(n) if n.value == "READONLYREST_ENABLE" => Some("true")
    case EnvVarName(n) if n.value == "USER1_PASS" => Some("user1:passwd")
    case _ => None
  }
}
