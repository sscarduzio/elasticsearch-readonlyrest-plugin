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
import tech.beshu.ror.accesscontrol.blocks.metadata.{KibanaPolicy, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaApp.FullNameKibanaApp
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.orders.forbiddenCauseOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.containers.{LdapContainer, Wiremock}
import tech.beshu.ror.utils.misc.ScalaUtils.StringOps

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

  override protected val httpClientsFactory: HttpClientsFactory = HttpClientsFactory.default()

  override protected def settingsYaml: String =
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
       |    groups_endpoint: "http://${wiremock.host}:${wiremock.portProvider.providePort()}/groups"
       |    auth_token_name: "user"
       |    auth_token_passed_as: QUERY_PARAM
       |    response_groups_json_path: "$$..groups[?(@.name)].name"
       |
       |  - name: Service2
       |    groups_endpoint: "http://${wiremock.host}:${wiremock.portProvider.providePort()}/groups"
       |    auth_token_name: "user"
       |    auth_token_passed_as: QUERY_PARAM
       |    response_groups_json_path: "$$..groups[?(@.name)].name"
       |
       |  - name: Service3
       |    groups_endpoint: "http://${wiremock.host}:${wiremock.portProvider.providePort()}/groups"
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
          def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
            metadata.groupsMetadata.keys.toList should be(GroupId("group3") :: GroupId("group1") :: Nil)

            val group3Metadata = metadata.groupsMetadata(GroupId("group3"))
            group3Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("User 1 - index1"))
            group3Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user1")))
            group3Metadata.userOrigin should be(None)
            group3Metadata.kibanaPolicy should be(None)

            val group1Metadata = metadata.groupsMetadata(GroupId("group1"))
            group1Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("User 1 - index2"))
            group1Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user1")))
            group1Metadata.userOrigin should be(None)
            group1Metadata.kibanaPolicy should be(None)
          }

          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user1:pass"))
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
            assertAllowUserMetadataWithGroupsResponse(userMetadata)
          }
        }
        "several blocks are matched and current group is set" in {
          def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
            metadata.groupsMetadata.keys.toList should be(GroupId("group5") :: GroupId("group6") :: Nil)

            val group5Metadata = metadata.groupsMetadata(GroupId("group5"))
            group5Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("User 4 - index1"))
            group5Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user4")))
            group5Metadata.userOrigin should be(None)
            group5Metadata.kibanaPolicy should be(Some(KibanaPolicy(
              access = KibanaAccess.Unrestricted,
              index = Some(kibanaIndexName("user4_group5_kibana_index")),
              templateIndex = None,
              hiddenApps = Set.empty,
              allowedApiPaths = Set.empty,
              genericMetadata = None
            )))

            val group6Metadata = metadata.groupsMetadata(GroupId("group6"))
            group6Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("User 4 - index2"))
            group6Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user4")))
            group6Metadata.userOrigin should be(None)
            group6Metadata.kibanaPolicy should be(Some(KibanaPolicy(
              access = KibanaAccess.Unrestricted,
              index = Some(kibanaIndexName("user4_group6_kibana_index")),
              templateIndex = None,
              hiddenApps = Set.empty,
              allowedApiPaths = Set.empty,
              genericMetadata = None
            )))
          }

          val loginRequest = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user4:pass"), currentGroupHeader("group6")
          )
          val (loginResponse, _) = acl.handleMetadataRequest(loginRequest).runSyncUnsafe()
          inside(loginResponse) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
            assertAllowUserMetadataWithGroupsResponse(userMetadata)
          }

          val switchTenancyRequest = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user4:pass"), currentGroupHeader("group5")
          )
          val (switchTenancyResponse, _) = acl.handleMetadataRequest(switchTenancyRequest).runSyncUnsafe()
          inside(switchTenancyResponse) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
            assertAllowUserMetadataWithGroupsResponse(userMetadata)
          }
        }
        "at least one block is matched" in {
          def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
            metadata.groupsMetadata.keys.toList should be(GroupId("group2") :: Nil)

            val group2Metadata = metadata.groupsMetadata(GroupId("group2"))
            group2Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("User 2"))
            group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user2")))
            group2Metadata.userOrigin should be(None)
            group2Metadata.kibanaPolicy should be(Some(KibanaPolicy(
              access = KibanaAccess.RO,
              index = Some(kibanaIndexName("user2_kibana_index")),
              templateIndex = None,
              hiddenApps = Set(FullNameKibanaApp("user2_app1"), FullNameKibanaApp("user2_app2")),
              allowedApiPaths = Set(
                KibanaAllowedApiPath(AllowedHttpMethod.Any, JavaRegex.compile("^/api/spaces/.*$").get),
                KibanaAllowedApiPath(AllowedHttpMethod.Specific(HttpMethod.Get), JavaRegex.compile("""^/api/spaces\?test\=12\.2$""").get)
              ),
              genericMetadata = None
            )))
          }

          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user2:pass"))
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
            assertAllowUserMetadataWithGroupsResponse(userMetadata)
          }
        }
        "block with no available groups collected is matched" in {
          def assertAllowUserMetadataWithoutGroupsResponse(metadata: UserMetadata.WithoutGroups) = {
            metadata.metadataOrigin.blockContext.block.name should be(Block.Name("User 3"))
            metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user3")))
            metadata.userOrigin should be(None)
            metadata.kibanaPolicy should be(Some(KibanaPolicy(
              access = KibanaAccess.Unrestricted,
              index = Some(kibanaIndexName("user3_kibana_index")),
              templateIndex = None,
              hiddenApps = Set(FullNameKibanaApp("user3_app1"), FullNameKibanaApp("user3_app2")),
              allowedApiPaths = Set.empty,
              genericMetadata = None
            )))
          }

          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user3:pass"))
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case Allowed(userMetadata:UserMetadata.WithoutGroups) =>
            assertAllowUserMetadataWithoutGroupsResponse(userMetadata)
          }
        }
        "available groups are collected from all blocks with external services" when {
          "the service is some HTTP service" in {
            def assertUser5Response(metadata: UserMetadata.WithGroups) = {
              metadata.groupsMetadata.keys.toList should be(GroupId("service1_group1") :: GroupId("service1_group2") :: Nil)

              val group1Metadata = metadata.groupsMetadata(GroupId("service1_group1"))
              group1Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("SERVICE1 user5 (1)"))
              group1Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user5")))

              val group2Metadata = metadata.groupsMetadata(GroupId("service1_group2"))
              group2Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("SERVICE1 user5 (2)"))
              group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user5")))
            }

            def assertUser7Response(metadata: UserMetadata.WithGroups) = {
              metadata.groupsMetadata.keys.toList should be(GroupId("service3_group1") :: Nil)

              val group1Metadata = metadata.groupsMetadata(GroupId("service3_group1"))
              group1Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("SERVICE3 user7"))
              group1Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user7")))
            }

            val request1 = MockRequestContext.metadata.withHeaders(header("X-Forwarded-User", "user5"))
            val (result1, _) = acl.handleMetadataRequest(request1).runSyncUnsafe()

            inside(result1) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
              assertUser5Response(userMetadata)
            }

            val request2 = MockRequestContext.metadata.withHeaders(
              header("X-Forwarded-User", "user5"), currentGroupHeader("service1_group2")
            )
            val (result2, _) = acl.handleMetadataRequest(request2).runSyncUnsafe()

            inside(result2) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
              assertUser5Response(userMetadata)
            }

            val request3 = MockRequestContext.metadata.withHeaders(
              header("X-Forwarded-User", "user7"), currentGroupHeader("service3_group1")
            )
            val (result3, _) = acl.handleMetadataRequest(request3).runSyncUnsafe()

            inside(result3) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
              assertUser7Response(userMetadata)
            }
          }
          "the service is LDAP" in {
            def assertUser6Response(metadata: UserMetadata.WithGroups) = {
              metadata.groupsMetadata.keys.toList should be(GroupId("ldap2_group1") :: GroupId("ldap2_group2") :: Nil)

              val group1Metadata = metadata.groupsMetadata(GroupId("ldap2_group1"))
              group1Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("LDAP2 user6 (1)"))
              group1Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user6")))

              val group2Metadata = metadata.groupsMetadata(GroupId("ldap2_group2"))
              group2Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("LDAP2 user6 (2)"))
              group2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user6")))
            }

            val request1 = MockRequestContext.metadata.withHeaders(basicAuthHeader("user6:user2"))
            val (result1, _) = acl.handleMetadataRequest(request1).runSyncUnsafe()

            inside(result1) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
              assertUser6Response(userMetadata)
            }

            val request2 = MockRequestContext.metadata.withHeaders(
              basicAuthHeader("user6:user2"), currentGroupHeader("ldap2_group2")
            )
            val (result2, _) = acl.handleMetadataRequest(request2).runSyncUnsafe()

            inside(result2) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
              assertUser6Response(userMetadata)
            }
          }
        }
        "we allow RW access to multiple tenants and RW access to its indices" in {
          def assertAllowUserMetadataWithGroupsResponse(metadata: UserMetadata.WithGroups) = {
            metadata.groupsMetadata.keys.toList should be(GroupId("tracy_tenant1") :: GroupId("tracy_tenant2") :: Nil)

            val tenant1Metadata = metadata.groupsMetadata(GroupId("tracy_tenant1"))
            tenant1Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("Allow RW access to tenant1 Kibana"))
            tenant1Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user9")))
            tenant1Metadata.userOrigin should be(None)
            tenant1Metadata.kibanaPolicy should be(Some(KibanaPolicy(
              access = KibanaAccess.RW,
              index = Some(kibanaIndexName(".kib_tracy_tenant1")),
              templateIndex = None,
              hiddenApps = Set.empty,
              allowedApiPaths = Set.empty,
              genericMetadata = None
            )))

            val tenant2Metadata = metadata.groupsMetadata(GroupId("tracy_tenant2"))
            tenant2Metadata.metadataOrigin.blockContext.block.name should be(Block.Name("Allow RW access to tenant2 Kibana"))
            tenant2Metadata.loggedUser should be(DirectlyLoggedUser(User.Id("user9")))
            tenant2Metadata.userOrigin should be(None)
            tenant2Metadata.kibanaPolicy should be(Some(KibanaPolicy(
              access = KibanaAccess.RW,
              index = Some(kibanaIndexName(".kib_tracy_tenant2")),
              templateIndex = None,
              hiddenApps = Set.empty,
              allowedApiPaths = Set.empty,
              genericMetadata = None
            )))
          }

          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user9:pass"))
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case Allowed(userMetadata@UserMetadata.WithGroups(_)) =>
            assertAllowUserMetadataWithGroupsResponse(userMetadata)
          }
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("userXXX:pass"))
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case r@ForbiddenByMismatched(_) =>
            r.causes should be(NonEmptySet.of[ForbiddenCause](OperationNotAllowed))
          }
        }
        "current group is set but it doesn't exist on available groups list" in {
          val request = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user4:pass"), currentGroupHeader("group7")
          )
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case r@ForbiddenByMismatched(_) =>
            r.causes should be(NonEmptySet.of[ForbiddenCause](OperationNotAllowed))
          }
        }
        "block with no available groups collected is matched and current group is set" in {
          val request = MockRequestContext.metadata.withHeaders(
            basicAuthHeader("user3:pass"), currentGroupHeader("group7")
          )
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case r@ForbiddenByMismatched(_) =>
            r.causes should be(NonEmptySet.of[ForbiddenCause](OperationNotAllowed))
          }
        }
        "request was matched only by the block with forbid policy" in {
          val request = MockRequestContext.metadata.withHeaders(basicAuthHeader("user8:pass"))
          val (result, _) = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result) { case Forbidden(blockContext) =>
            blockContext.block.name should be(Block.Name("User 8"))
            blockContext.block.policy should be(Block.Policy.Forbid(Some("you are unauthorized to access this resource")))
            assertBlockContext(blockContext)(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user8")))
            )
          }
        }
      }
    }
  }
}
