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
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableRequestBlockContext, GeneralIndexRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Json.{JsonTree, JsonValue}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Jwt => _, _}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.containers.NonStoppableLdapContainer
import tech.beshu.ror.utils.misc.JwtUtils._
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.UniqueList
import tech.beshu.ror.utils.TestsUtils.unsafeNes

import java.util.Base64

class VariableResolvingYamlLoadedAccessControlTests extends AnyWordSpec
  with BaseYamlLoadedAccessControlTest
  with ForAllTestContainer
  with Inside {

  override val container: NonStoppableLdapContainer = SingletonLdapContainers.ldap1

  override protected val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  private lazy val (pub, secret) = Random.generateRsaRandomKeys

  override protected def configYaml: String =
    s"""
       |readonlyrest:
       |
       |  enable: $${READONLYREST_ENABLE}
       |
       |  variables_function_aliases:
       |   - custom_replace: replace_all(\"\\\\d\",\"X\") # replace digits with X
       |
       |  access_control_rules:
       |   - name: "CONTAINER ADMIN"
       |     type: allow
       |     auth_key: admin:container
       |
       |   - name: "Kibana metadata resolving test"
       |     type: allow
       |     users: ["user9"]
       |     jwt_auth:
       |       name: "jwt3"
       |     kibana:
       |       access: ro
       |       metadata:
       |         a: "jwt_value_@{jwt:tech.beshu.mainGroupsString}"
       |         b: "@{jwt:user_id_list}"
       |         c: "jwt_value_transformed_@{jwt:tech.beshu.mainGroupsString}#{replace_first(\\"j\\",\\"g\\").to_uppercase}"
       |
       |   - name: "Group id from header variable"
       |     type: allow
       |     groups: ["g4", "@{X-my-group-id-1}", "@{header:X-my-group-id-2}#{func(custom_replace)}" ]
       |
       |   - name: "Group id from env variable"
       |     type: allow
       |     groups: ["g@{env:sys_group_1}#{to_lowercase}"]
       |
       |   - name: "Group id from env variable (old syntax)"
       |     type: allow
       |     groups: ['g$${sys_group_2}#{replace_first("s","")}']
       |
       |   - name: "Variables usage in filter"
       |     type: allow
       |     filter: '{"bool": { "must": { "terms": { "user_id": [@{jwt:user_id_list}] }}}}'
       |     jwt_auth: "jwt3"
       |     users: ["user5"]
       |
       |   - name: "Group id from jwt variable (array)"
       |     type: allow
       |     jwt_auth:
       |       name: "jwt1"
       |     indices: ['g@explode{jwt:tech.beshu.mainGroup}#{replace_all("j","jj")}']
       |
       |   - name: "Group id from jwt variable"
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
       |       - gX: group3
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
       |      host: $${LDAP_HOST}
       |      port: $${LDAP_PORT}
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
    "is configured using config above" should {
      "allow to proceed" when {
        "old style header variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-id-1", "g3"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group id from header variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupId("g3")),
              availableGroups = UniqueList.of(group("g3"))
            ) {
              blockContext
            }
          }
        }
        "new style header variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-id-2", "g3"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group id from header variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupId("g3")),
              availableGroups = UniqueList.of(group("g3"))
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

          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group id from env variable (old syntax)"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
              currentGroup = Some(GroupId("gs2")),
              availableGroups = UniqueList.of(group("gs2"))
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

          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group id from env variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupId("gs1")),
              availableGroups = UniqueList.of(group("gs1"))
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
            filteredIndices = Set(clusterIndexName("gjj1"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result.result) { case RegularRequestResult.Allow(blockContext: GeneralIndexRequestBlockContext, block) =>
            block.name should be(Block.Name("Group id from jwt variable (array)"))
            blockContext.userMetadata should be(
              UserMetadata
                .empty
                .withLoggedUser(DirectlyLoggedUser(User.Id("user3")))
                .withJwtToken(domain.Jwt.Payload(jwt.defaultClaims()))
            )
            blockContext.filteredIndices should be(Set(clusterIndexName("gjj1")))
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

          inside(result.result) { case RegularRequestResult.Allow(blockContext: GeneralIndexRequestBlockContext, block) =>
            block.name should be(Block.Name("Group id from jwt variable"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("user4")))
                .withJwtToken(domain.Jwt.Payload(jwt.defaultClaims()))
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

          inside(result.result) { case RegularRequestResult.Allow(blockContext: FilterableRequestBlockContext, block) =>
            block.name should be(Block.Name("Variables usage in filter"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("user5")))
                .withJwtToken(domain.Jwt.Payload(jwt.defaultClaims()))
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

          inside(result.result) { case RegularRequestResult.Allow(blockContext: FilterableRequestBlockContext, block) =>
            block.name should be(Block.Name("LDAP groups explode"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("cartman")))
                .withCurrentGroupId(GroupId("g1"))
                .withAvailableGroups(UniqueList.of(group("g1"), group("g3")))
            )
            blockContext.filteredIndices should be(Set(clusterIndexName("test-g1"), clusterIndexName("test-g3")))
            blockContext.responseHeaders should be(Set.empty)
            blockContext.filter should be(Some(Filter("""{"bool": { "must": { "terms": { "group_id": ["g1","g3"] }}}}""")))
          }
        }
        "kibana.metadata has variables used" in {
          val jwt = Jwt(secret, claims = List(
            "userId" := "user9",
            "user_id_list" := List("alice", "bob"),
            "tech" :-> "beshu" :-> "mainGroupsString" := "j0,j3"
          ))

          val request = MockRequestContext.metadata.copy(
            headers = Set(bearerHeader(jwt))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          inside(result.result) {
            case RegularRequestResult.Allow(blockContext, block) =>
              block.name should be(Block.Name("Kibana metadata resolving test"))
              blockContext.userMetadata should be(
                UserMetadata
                  .from(request)
                  .withLoggedUser(DirectlyLoggedUser(User.Id("user9")))
                  .withKibanaAccess(KibanaAccess.RO)
                  .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
                  .withKibanaMetadata(
                    JsonTree.Object(Map(
                      "a" -> JsonTree.Value(JsonValue.StringValue("jwt_value_j0,j3")),
                      "b" -> JsonTree.Value(JsonValue.StringValue("\"alice\",\"bob\"")),
                      "c" -> JsonTree.Value(JsonValue.StringValue("jwt_value_transformed_G0,J3"))
                    ))
                  )
                  .withJwtToken(domain.Jwt.Payload(jwt.defaultClaims()))
              )
              blockContext.responseHeaders should be(Set.empty)
          }
        }
      }
    }
  }

  override implicit protected def envVarsProvider: EnvVarsProvider = {
    case EnvVarName(n) if n.value == "sys_group_1" => Some("S1")
    case EnvVarName(n) if n.value == "sys_group_2" => Some("ss2")
    case EnvVarName(n) if n.value == "READONLYREST_ENABLE" => Some("true")
    case EnvVarName(n) if n.value == "USER1_PASS" => Some("user1:passwd")
    case EnvVarName(n) if n.value == "LDAP_HOST" => Some(SingletonLdapContainers.ldap1.ldapHost)
    case EnvVarName(n) if n.value == "LDAP_PORT" => Some(s"${SingletonLdapContainers.ldap1.ldapPort}")
    case _ => None
  }
}
