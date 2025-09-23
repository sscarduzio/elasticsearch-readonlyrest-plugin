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

import cats.data.NonEmptySet
import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.AccessControlList.ForbiddenCause
import tech.beshu.ror.accesscontrol.AccessControlList.ForbiddenCause.OperationNotAllowed
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult.*
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaApp.FullNameKibanaApp
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, HttpClientsFactory}
import tech.beshu.ror.accesscontrol.orders.forbiddenCauseOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.containers.{LdapContainer, Wiremock}
import tech.beshu.ror.utils.misc.ScalaUtils.StringOps
import tech.beshu.ror.utils.uniquelist.UniqueList

class CurrentUserMetadataAccessControlTests
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with BeforeAndAfterAll
    with ForAllTestContainer
    with Inside {

  private val mappings = List(
    "/current_user_metadata_access_control_tests/wiremock_service1_user5.json",
    "/current_user_metadata_access_control_tests/wiremock_service2_user6.json",
    "/current_user_metadata_access_control_tests/wiremock_service3_user7.json",
  )

  private val wiremock = Wiremock.create(mappings)

  private val ldap1 = LdapContainer.create("LDAP1",
    "current_user_metadata_access_control_tests/ldap_ldap1_user5.ldif"
  )
  private val ldap2 = LdapContainer.create("LDAP2",
    "current_user_metadata_access_control_tests/ldap_ldap2_user6.ldif"
  )

  override val container: MultipleContainers = MultipleContainers(wiremock.container, ldap1, ldap2)

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
    httpClientsFactory.shutdown()
  }

  override protected val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected val httpClientsFactory: HttpClientsFactory = new AsyncHttpClientsFactory

  override protected def configYaml: String =
    s"""
       |readonlyrest:
       |
       |  access_control_rules:
       |
       |  - name: "User 1 - index1"
       |    users: ["user1"]
       |    groups: [group2, group3]
       |
       |  - name: "User 1 - index2"
       |    users: ["user1"]
       |    groups: [group2, group1]
       |
       |  - name: "User 2"
       |    users: ["user2"]
       |    groups: [group2, group3]
       |    uri_re: ^/_readonlyrest/metadata/current_user/?$$
       |    kibana:
       |      access: ro
       |      index: "user2_kibana_index"
       |      hide_apps: ["user2_app1", "user2_app2"]
       |      allowed_api_paths:
       |        - "^/api/spaces/.*$$"
       |        - http_method: GET
       |          http_path: "/api/spaces?test=12.2"
       |
       |  - name: "User 3"
       |    auth_key: "user3:pass"
       |    kibana:
       |      access: unrestricted
       |      index: "user3_kibana_index"
       |      hide_apps: ["user3_app1", "user3_app2"]
       |
       |  - name: "User 4 - index1"
       |    users: ["user4"]
       |    kibana:
       |      access: unrestricted
       |      index: "user4_group5_kibana_index"
       |    groups: [group5]
       |
       |  - name: "User 4 - index2"
       |    users: ["user4"]
       |    kibana:
       |      access: unrestricted
       |      index: "user4_group6_kibana_index"
       |    groups: [group6, group5]
       |
       |  - name: "SERVICE1 user5 (1)"
       |    proxy_auth: "user5"
       |    groups_provider_authorization:
       |      user_groups_provider: "Service1"
       |      groups: ["service1_group1"]
       |
       |  - name: "SERVICE1 user5 (2)"
       |    proxy_auth: "user5"
       |    groups_provider_authorization:
       |      user_groups_provider: "Service1"
       |      groups: ["service1_group2"]
       |
       |  - name: "LDAP1 user5 (3)"
       |    ldap_auth:
       |      name: "Ldap1"
       |      groups: ["ldap1_group1"]
       |
       |  - name: "LDAP2 user6 (1)"
       |    ldap_auth:
       |      name: "Ldap2"
       |      groups: ["ldap2_group1"]
       |
       |  - name: "LDAP2 user6 (2)"
       |    ldap_auth:
       |      name: "Ldap2"
       |      groups: ["ldap2_group2"]
       |
       |  - name: "SERVICE2 user6 (3)"
       |    proxy_auth: "user6"
       |    groups_provider_authorization:
       |      user_groups_provider: "Service2"
       |      groups: ["service2_group2"]
       |
       |  - name: "SERVICE3 user7"
       |    proxy_auth: "user7"
       |    groups_provider_authorization:
       |      user_groups_provider: "Service3"
       |      groups: ["service3_group1"]
       |
       |  - name: "User 8"
       |    type:
       |      policy: forbid
       |      response_message: "you are unauthorized to access this resource"
       |    auth_key: user8:pass
       |
       |  - name: "Allow RW access to all tracy-* indices"
       |    groups_or: ["tracy_tenant1", "tracy_tenant2"]
       |    indices: ["tracy-*"]
       |
       |  - name: "Allow RW access to tenant1 Kibana"
       |    groups_or: ["tracy_tenant1"]
       |    indices: ["tracy-*"]
       |    kibana:
       |      access: rw
       |      index: ".kib_tracy_tenant1"
       |
       |  - name: "Allow RW access to tenant2 Kibana"
       |    groups_or: ["tracy_tenant2"]
       |    indices: ["tracy-*"]
       |    kibana:
       |      access: rw
       |      index: ".kib_tracy_tenant2"
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
       |  - username: user4
       |    groups:
       |      - id: group5
       |        name: "Group 5"
       |      - id : group6
       |        name: "Group 6"
       |    auth_key: "user4:pass"
       |
       |  - username: user9
       |    groups: [tracy_tenant1, tracy_tenant2]
       |    auth_key: "user9:pass"
       |
       |  user_groups_providers:
       |
       |  - name: Service1
       |    groups_endpoint: "http://${wiremock.host}:${wiremock.port}/groups"
       |    auth_token_name: "user"
       |    auth_token_passed_as: QUERY_PARAM
       |    response_groups_json_path: "$$..groups[?(@.name)].name"
       |
       |  - name: Service2
       |    groups_endpoint: "http://${wiremock.host}:${wiremock.port}/groups"
       |    auth_token_name: "user"
       |    auth_token_passed_as: QUERY_PARAM
       |    response_groups_json_path: "$$..groups[?(@.name)].name"
       |
       |  - name: Service3
       |    groups_endpoint: "http://${wiremock.host}:${wiremock.port}/groups"
       |    auth_token_name: "user"
       |    auth_token_passed_as: QUERY_PARAM
       |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
       |    response_group_names_json_path: "$$..groups[?(@.name)].name"
       |
       |  ldaps:
       |  - name: Ldap1
       |    host: "${ldap1.ldapHost}"
       |    port: ${ldap1.ldapPort}
       |    ssl_enabled: false                                        # default true
       |    ssl_trust_all_certs: true                                 # default false
       |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |    bind_password: "password"                                 # skip for anonymous bind
       |    connection_pool_size: 10                                  # default 30
       |    connection_timeout_in_sec: 10                             # default 1
       |    request_timeout_in_sec: 10                                # default 1
       |    cache_ttl_in_sec: 60                                      # default 0 - cache disabled
       |    users:
       |      search_user_base_DN: "ou=People,dc=example,dc=com"
       |      user_id_attribute: "uid"                                # default "uid"
       |    groups:
       |      search_groups_base_DN: "ou=Groups,dc=example,dc=com"
       |      unique_member_attribute: "uniqueMember"                 # default "uniqueMember"
       |
       |  - name: Ldap2
       |    host: "${ldap2.ldapHost}"
       |    port: ${ldap2.ldapPort}
       |    ssl_enabled: false                                        # default true
       |    ssl_trust_all_certs: true                                 # default false
       |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |    bind_password: "password"                                 # skip for anonymous bind
       |    connection_pool_size: 10                                  # default 30
       |    connection_timeout_in_sec: 10                             # default 1
       |    request_timeout_in_sec: 10                                # default 1
       |    cache_ttl_in_sec: 60                                      # default 0 - cache disabled
       |    users:
       |      search_user_base_DN: "ou=People,dc=example,dc=com"
       |      user_id_attribute: "uid"                                # default "uid"
       |    groups:
       |      search_groups_base_DN: "ou=Groups,dc=example,dc=com"
       |      unique_member_attribute: "uniqueMember"                 # default "uniqueMember"
       |
    """.stripMarginAndReplaceWindowsLineBreak

  "An ACL" when {
    "handling current user metadata kibana plugin request" should {
      "allow to proceed" when {
        "several blocks are matched" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user1:pass"))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user1"))))
            userMetadata.currentGroupId should be(Some(GroupId("group3")))
            userMetadata.availableGroups.toCovariantSet should be(Set(group("group3"), group("group1")))
            userMetadata.kibanaIndex should be(None)
            userMetadata.hiddenKibanaApps should be(Set.empty)
            userMetadata.allowedKibanaApiPaths should be(Set.empty)
            userMetadata.kibanaAccess should be(None)
            userMetadata.userOrigin should be(None)
          }
        }
        "several blocks are matched and current group is set" in {
          val loginRequest = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user4:pass"), currentGroupHeader("group6")
          )
          val loginResponse = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
          inside(loginResponse.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user4"))))
            userMetadata.currentGroupId should be(Some(GroupId("group6")))
            userMetadata.availableGroups.toCovariantSet should be(Set(group("group5", "Group 5"), group("group6", "Group 6")))
            userMetadata.kibanaIndex should be(Some(kibanaIndexName("user4_group6_kibana_index")))
            userMetadata.hiddenKibanaApps should be(Set.empty)
            userMetadata.allowedKibanaApiPaths should be(Set.empty)
            userMetadata.kibanaAccess should be(Some(KibanaAccess.Unrestricted))
            userMetadata.userOrigin should be(None)
          }

          val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user4:pass"), currentGroupHeader("group5")
          )
          val switchTenancyResponse = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
          inside(switchTenancyResponse.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user4"))))
            userMetadata.currentGroupId should be(Some(GroupId("group5")))
            userMetadata.availableGroups.toCovariantSet should be(Set(group("group5", "Group 5"), group("group6", "Group 6")))
            userMetadata.kibanaIndex should be(Some(kibanaIndexName("user4_group5_kibana_index")))
            userMetadata.hiddenKibanaApps should be(Set.empty)
            userMetadata.allowedKibanaApiPaths should be(Set.empty)
            userMetadata.kibanaAccess should be(Some(KibanaAccess.Unrestricted))
            userMetadata.userOrigin should be(None)
          }
        }
        "at least one block is matched" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user2:pass"))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user2"))))
            userMetadata.currentGroupId should be(Some(GroupId("group2")))
            userMetadata.availableGroups.toCovariantSet should be(Set(group("group2")))
            userMetadata.kibanaIndex should be(Some(kibanaIndexName("user2_kibana_index")))
            userMetadata.hiddenKibanaApps should be(Set(FullNameKibanaApp("user2_app1"), FullNameKibanaApp("user2_app2")))
            userMetadata.allowedKibanaApiPaths should be(Set(
              KibanaAllowedApiPath(AllowedHttpMethod.Any, JavaRegex.compile("^/api/spaces/.*$").get),
              KibanaAllowedApiPath(AllowedHttpMethod.Specific(HttpMethod.Get), JavaRegex.compile("""^/api/spaces\?test\=12\.2$""").get)
            ))
            userMetadata.kibanaAccess should be(Some(KibanaAccess.RO))
            userMetadata.userOrigin should be(None)
          }
        }
        "block with no available groups collected is matched" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user3:pass"))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user3"))))
            userMetadata.currentGroupId should be(None)
            userMetadata.availableGroups should be(UniqueList.empty)
            userMetadata.kibanaIndex should be(Some(kibanaIndexName("user3_kibana_index")))
            userMetadata.hiddenKibanaApps should be(Set(FullNameKibanaApp("user3_app1"), FullNameKibanaApp("user3_app2")))
            userMetadata.allowedKibanaApiPaths should be(Set.empty)
            userMetadata.kibanaAccess should be(Some(KibanaAccess.Unrestricted))
            userMetadata.userOrigin should be(None)
          }
        }
        "available groups are collected from all blocks with external services" when {
          "the service is some HTTP service" in {
            val request1 = MockRequestContext.metadata.withHeaders(header("X-Forwarded-User", "user5"))
            val result1 = acl.handleMetadataRequest(request1).runSyncUnsafe()

            inside(result1.result) { case Allow(userMetadata, _) =>
              userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user5"))))
              userMetadata.availableGroups.toCovariantSet should be(Set(group("service1_group1"), group("service1_group2")))
            }

            val request2 = MockRequestContext.metadata.withHeaders(
              header("X-Forwarded-User", "user5"), currentGroupHeader("service1_group2")
            )
            val result2 = acl.handleMetadataRequest(request2).runSyncUnsafe()

            inside(result2.result) { case Allow(userMetadata, _) =>
              userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user5"))))
              userMetadata.availableGroups.toCovariantSet should be(Set(group("service1_group1"), group("service1_group2")))
            }

            val request3 = MockRequestContext.metadata.withHeaders(
              header("X-Forwarded-User", "user7"), currentGroupHeader("service3_group1")
            )
            val result3 = acl.handleMetadataRequest(request3).runSyncUnsafe()

            inside(result3.result) { case Allow(userMetadata, _) =>
              userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user7"))))
              userMetadata.availableGroups.toCovariantSet should be(Set(group("service3_group1", "Group 1")))
            }
          }
          "the service is LDAP" in {
            val request1 = MockRequestContext.metadata.withHeaders(basicAuthHeader("user6:user2"))
            val result1 = acl.handleMetadataRequest(request1).runSyncUnsafe()

            inside(result1.result) { case Allow(userMetadata, _) =>
              userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user6"))))
              userMetadata.availableGroups.toCovariantSet should be(Set(group("ldap2_group1"), group("ldap2_group2")))
            }

            val request2 = MockRequestContext.metadata.withHeaders(
              basicAuthHeader("user6:user2"), currentGroupHeader("ldap2_group2")
            )
            val result2 = acl.handleMetadataRequest(request2).runSyncUnsafe()

            inside(result2.result) { case Allow(userMetadata, _) =>
              userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user6"))))
              userMetadata.availableGroups.toCovariantSet should be(Set(group("ldap2_group1"), group("ldap2_group2")))
            }
          }
        }
        "we allow RW access to multiple tenants and RW access to its indices" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user9:pass"))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be(Some(DirectlyLoggedUser(User.Id("user9"))))
            userMetadata.currentGroupId should be(Some(GroupId("tracy_tenant1")))
            userMetadata.availableGroups.toCovariantSet should be(Set(group("tracy_tenant1"), group("tracy_tenant2")))
            userMetadata.kibanaIndex should be(Some(kibanaIndexName(".kib_tracy_tenant1")))
            userMetadata.hiddenKibanaApps should be(Set.empty)
            userMetadata.allowedKibanaApiPaths should be(Set.empty)
            userMetadata.kibanaAccess should be(Some(KibanaAccess.RW))
            userMetadata.userOrigin should be(None)
          }
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("userXXX:pass"))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case ForbiddenByMismatched(causes) =>
            causes should be(NonEmptySet.of[ForbiddenCause](OperationNotAllowed))
          }
        }
        "current group is set but it doesn't exist on available groups list" in {
          val request = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user4:pass"), currentGroupHeader("group7")
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case ForbiddenByMismatched(causes) =>
            causes should be(NonEmptySet.of[ForbiddenCause](OperationNotAllowed))
          }
        }
        "block with no available groups collected is matched and current group is set" in {
          val request = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user3:pass"), currentGroupHeader("group7")
          )
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case ForbiddenByMismatched(causes) =>
            causes should be(NonEmptySet.of[ForbiddenCause](OperationNotAllowed))
          }
        }
        "request was matched only by the block with forbid policy" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user8:pass"))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case ForbiddenBy(blockContext, block) =>
            block.name should be(Block.Name("User 8"))
            block.policy should be(Block.Policy.Forbid(Some("you are unauthorized to access this resource")))
            assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("user8")))) {
              blockContext
            }
          }
        }
      }
    }
  }
}
