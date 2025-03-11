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
package tech.beshu.ror.unit.acl.factory.decoders.rules.auth
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.Config
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.Config.*
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.Config.GroupsConfig.{GroupIdsConfig, GroupNamesConfig}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.{GroupId, GroupIdPattern}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.mocks.MockHttpClientsFactoryWithFixedHttpClient
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.*
import scala.language.postfixOps

class ExternalAuthorizationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[ExternalAuthorizationRule] with MockFactory with Inside {

  "An ExternalAuthorizationRule" should {
    "be able to be loaded from config" when {
      "one authorization service is declared" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService1"
              |      groups: ["g*"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service.serviceTimeout.value should be(5 seconds)
              service shouldBe a[HttpExternalAuthorizationService]
              service.asInstanceOf[HttpExternalAuthorizationService].config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Get,
                tokenName = AuthTokenName("user"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = None
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingQueryParam,
                defaultHeaders = Set.empty,
                defaultQueryParams = Set.empty
              ))
              groupsLogic should be(
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdLike.from("g*"))))
              )
              groupsLogic.asInstanceOf[GroupsLogic.AnyOf].permittedGroupIds.ids.head shouldBe a[GroupIdPattern]
              users should be(UniqueNonEmptyList.of(User.Id("user1")))
            }
          }
        )
      }
      "more than one authorization service is declared" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService2
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |  - name: GroupsService2
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user2"
              |    auth_token_passed_as: HEADER
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService2"))
              service.serviceTimeout.value should be(5 seconds)
              service shouldBe a[HttpExternalAuthorizationService]
              service.asInstanceOf[HttpExternalAuthorizationService].config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Get,
                tokenName = AuthTokenName("user2"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = None
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingHeader,
                defaultHeaders = Set.empty,
                defaultQueryParams = Set.empty
              ))
              groupsLogic should be(
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"))))
              )
              users should be(UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2")))
            }
          }
        )
      }
      "authorization rule can have caching declared at rule level" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService1"
              |      groups: ["group3"]
              |      users: "user1"
              |      cache_ttl_in_sec: 60
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service.serviceTimeout.value should be(5 seconds)
              service shouldBe a[CacheableExternalAuthorizationServiceDecorator]
              val cachableService = service.asInstanceOf[CacheableExternalAuthorizationServiceDecorator]
              cachableService.underlying shouldBe a[HttpExternalAuthorizationService]
              val httpService = cachableService.underlying.asInstanceOf[HttpExternalAuthorizationService]
              httpService.config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Get,
                tokenName = AuthTokenName("user"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = None
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingQueryParam,
                defaultHeaders = Set.empty,
                defaultQueryParams = Set.empty
              ))
              groupsLogic should be(
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"))))
              )
              users should be(UniqueNonEmptyList.of(User.Id("user1")))
            }
          }
        )
      }
      "no user is declared" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service.serviceTimeout.value should be(5 seconds)
              service shouldBe a[HttpExternalAuthorizationService]
              service.asInstanceOf[HttpExternalAuthorizationService].config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Get,
                tokenName = AuthTokenName("user"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = None
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingQueryParam,
                defaultHeaders = Set.empty,
                defaultQueryParams = Set.empty
              ))
              groupsLogic should be(
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"))))
              )
              users should be(UniqueNonEmptyList.of(User.Id("*")))
            }
          }
        )
      }
      "old format of group IDs json path property is used" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService1"
              |      groups: ["g*"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_groups_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service.serviceTimeout.value should be(5 seconds)
              service shouldBe a[HttpExternalAuthorizationService]
              service.asInstanceOf[HttpExternalAuthorizationService].config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Get,
                tokenName = AuthTokenName("user"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = None
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingQueryParam,
                defaultHeaders = Set.empty,
                defaultQueryParams = Set.empty
              ))
              groupsLogic should be(
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdLike.from("g*"))))
              )
              groupsLogic.asInstanceOf[GroupsLogic.AnyOf].permittedGroupIds.ids.head shouldBe a[GroupIdPattern]
              users should be(UniqueNonEmptyList.of(User.Id("user1")))
            }
          }
        )
      }
      "group names json path is used" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService1"
              |      groups: ["g*"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    response_group_names_json_path: "$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service.serviceTimeout.value should be(5 seconds)
              service shouldBe a[HttpExternalAuthorizationService]
              service.asInstanceOf[HttpExternalAuthorizationService].config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Get,
                tokenName = AuthTokenName("user"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = Some(GroupNamesConfig(jsonPathFrom("$..groups[?(@.name)].name")))
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingQueryParam,
                defaultHeaders = Set.empty,
                defaultQueryParams = Set.empty
              ))
              groupsLogic should be(
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdLike.from("g*"))))
              )
              groupsLogic.asInstanceOf[GroupsLogic.AnyOf].permittedGroupIds.ids.head shouldBe a[GroupIdPattern]
              users should be(UniqueNonEmptyList.of(User.Id("user1")))
            }
          }
        )
      }
      "authorization service definition is declared using all available fields" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["g*", "r1"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: HEADER
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    response_group_names_json_path: "$..groups[?(@.name)].name"
              |    http_method: POST
              |    default_query_parameters: query1:value1;query2:value2
              |    default_headers: header1:hValue1; header2:hValue2
              |    cache_ttl_in_sec: 100
              |    validate: false
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service.serviceTimeout.value should be(5 seconds)
              service shouldBe a[CacheableExternalAuthorizationServiceDecorator]
              val cacheableService = service.asInstanceOf[CacheableExternalAuthorizationServiceDecorator]
              cacheableService.ttl.value should be(100 seconds)
              cacheableService.underlying shouldBe a[HttpExternalAuthorizationService]
              val underlyingService = cacheableService.underlying.asInstanceOf[HttpExternalAuthorizationService]
              underlyingService.config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Post,
                tokenName = AuthTokenName("user"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = Some(GroupNamesConfig(jsonPathFrom("$..groups[?(@.name)].name")))
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingHeader,
                defaultHeaders = Set(Header(("header1", "hValue1")), Header(("header2", "hValue2"))),
                defaultQueryParams = Set(QueryParam("query1", "value1"), QueryParam("query2", "value2"))
              ))
              groupsLogic should be(GroupsLogic.AnyOf(
                GroupIds(UniqueNonEmptyList.of(GroupIdLike.from("g*"), GroupIdLike.from("r1")))
              ))
              groupsLogic.asInstanceOf[GroupsLogic.AnyOf].permittedGroupIds.ids.head shouldBe a[GroupIdPattern]
              groupsLogic.asInstanceOf[GroupsLogic.AnyOf].permittedGroupIds.ids.tail.head shouldBe a[GroupId]
              users should be(UniqueNonEmptyList.of(User.Id("*")))
            }
          }
        )
      }
      "authorization service definition is declared using all available fields and custom http client settings" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: HEADER
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    response_group_names_json_path: "$..groups[?(@.name)].name"
              |    http_method: POST
              |    default_query_parameters: query1:value1;query2:value2
              |    default_headers: header1:hValue1; header2:hValue2
              |    cache_ttl_in_sec: 100
              |    http_connection_settings:
              |      connection_timeout_in_sec: 1
              |      connection_request_timeout_in_sec: 10
              |      connection_pool_size: 30
              |      validate: true
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, groupsLogic, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service.serviceTimeout.value should be(10 seconds)
              service shouldBe a[CacheableExternalAuthorizationServiceDecorator]
              val cacheableService = service.asInstanceOf[CacheableExternalAuthorizationServiceDecorator]
              cacheableService.ttl.value should be(100 seconds)
              cacheableService.underlying shouldBe a[HttpExternalAuthorizationService]
              val underlyingService = cacheableService.underlying.asInstanceOf[HttpExternalAuthorizationService]
              underlyingService.config should be(Config(
                url = urlFrom("http://localhost:8080/groups"),
                method = SupportedHttpMethod.Post,
                tokenName = AuthTokenName("user"),
                groupsConfig = GroupsConfig(
                  idsConfig = GroupIdsConfig(jsonPathFrom("$..groups[?(@.id)].id")),
                  namesConfig = Some(GroupNamesConfig(jsonPathFrom("$..groups[?(@.name)].name")))
                ),
                authTokenSendMethod = AuthTokenSendMethod.UsingHeader,
                defaultHeaders = Set(Header(("header1", "hValue1")), Header(("header2", "hValue2"))),
                defaultQueryParams = Set(QueryParam("query1", "value1"), QueryParam("query2", "value2"))
              ))
              groupsLogic should be(
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("group3"))))
              )
              users should be(UniqueNonEmptyList.of(User.Id("*")))
            }
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "authorization rule doesn't have service name declared" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      groups: ["group3"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """groups_provider_authorization:
                |  groups:
                |  - "group3"
                |  users: "user1"
                |""".stripMargin
            )))
          }
        )
      }
      "authorization rule doesn't have groups set declared" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService2"
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "groups_provider_authorization rule requires to define 'groups_any_of'/'groups_all_of'/'groups_not_any_of'/'groups_not_all_of' arrays"
            )))
          }
        )
      }
      "no authorization service with given name is declared" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService2"
              |      groups: ["group3"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find user groups provider with name: GroupsService2")))
          }
        )
      }
      "no authorization service is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService2"
              |      groups: ["group3"]
              |      users: user1
              |
              |  user_groups_providers:
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("user_groups_providers declared, but no definition found")))
          }
        )
      }
      "authorization service doesn't have a name" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService2"
              |      groups: ["group3"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- groups_endpoint: "http://localhost:8080/groups"
                |  auth_token_name: "user"
                |  auth_token_passed_as: "QUERY_PARAM"
                |  response_group_ids_json_path: "$..groups[?(@.id)].id"
                |""".stripMargin
            )))
          }
        )
      }
      "names of authorization services are not unique" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user2"
              |    auth_token_passed_as: HEADER
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "user_groups_providers definitions must have unique identifiers. Duplicates: GroupsService1"
            )))
          }
        )
      }
      "authorization service doesn't have endpoint defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: "GroupsService1"
              |      groups: ["group3"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- name: "GroupsService1"
                |  auth_token_name: "user"
                |  auth_token_passed_as: "QUERY_PARAM"
                |  response_group_ids_json_path: "$..groups[?(@.id)].id"
                |""".stripMargin
            )))
          }
        )
      }
      "authorization service endpoint url is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://malformed@{user}:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot convert value 'http://malformed@{user}:8080/groups' to url")))
          }
        )
      }
      "authorization service doesn't have token passing method defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- name: "GroupsService1"
                |  groups_endpoint: "http://localhost:8080/groups"
                |  auth_token_name: "user"
                |  response_group_ids_json_path: "$..groups[?(@.id)].id"
                |""".stripMargin
            )))
          }
        )
      }
      "authorization service token passing method is unknown" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user2"
              |    auth_token_passed_as: BODY
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Unknown value 'BODY' of 'auth_token_passed_as' attribute. Supported: 'HEADER', 'QUERY_PARAM'")))
          }
        )
      }
      "authorization service doesn't have groups JSON path defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "External authorization service 'GroupsService1' configuration is missing the 'response_group_ids_json_path' attribute"
            )))
          }
        )
      }
      "authorization service group IDs JSON path is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?.id)].id_malformed"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot compile '$..groups[?.id)].id_malformed' to JSON path")))
          }
        )
      }
      "authorization service group IDs JSON path is not defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("External authorization service 'GroupsService1' configuration is missing the 'response_group_ids_json_path' attribute")))
          }
        )
      }
      "authorization service group names JSON path is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    response_group_names_json_path: "$..groups[?.name)].name_malformed"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot compile '$..groups[?.name)].name_malformed' to JSON path")))
          }
        )
      }
      "authorization service group IDs JSON path is defined in the old and new syntax at once" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "External authorization service 'GroupsService1' configuration cannot have the 'response_groups_json_path' and 'response_group_ids_json_path' attributes defined at the same time"
            )))
          }
        )
      }
      "authorization service auth token name is not defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- name: "GroupsService1"
                |  groups_endpoint: "http://localhost:8080/groups"
                |  auth_token_passed_as: "QUERY_PARAM"
                |  response_group_ids_json_path: "$..groups[?(@.id)].id"
                |""".stripMargin
            )))
          }
        )
      }
      "authorization service auth token name is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: ""
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            inside(errors.head) { case DefinitionsLevelCreationError(MalformedValue(msg)) =>
              msg should include("name: \"GroupsService1\"")
              msg should include("auth_token_name: \"\"")
            }
          }
        )
      }
      "authorization service TTL value is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    cache_ttl_in_sec: hundred
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot convert value '\"hundred\"' to duration")))
          }
        )
      }
      "authorization service TTL value is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    cache_ttl_in_sec: -120
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -120 seconds")))
          }
        )
      }
      "authorization service http method is unsupported" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    http_method: DELETE
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Unknown value 'DELETE' of 'http_method' attribute. Supported: 'GET', 'POST'")))
          }
        )
      }
      "authorization service default query params are malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    default_query_parameters: "query:;query1:12345"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot parse pairs: query:")))
          }
        )
      }
      "authorization service default headers are malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    default_headers: "header1:;header2:12345"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot parse pairs: header1:")))
          }
        )
      }
      "custom http client settings is defined together with validate at rule level" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    validate: true
              |    http_connection_settings:
              |      validate: false
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("If 'http_connection_settings' are used, 'validate' should be placed in that section")))
          }
        )
      }
      "custom http client connection timeout is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    http_connection_settings:
              |      connection_timeout_in_sec: -10
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "custom http client request timeout is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    http_connection_settings:
              |      connection_request_timeout_in_sec: -10
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "custom http client connection pool size is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |      users: ["user1", "user2"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_group_ids_json_path: "$..groups[?(@.id)].id"
              |    http_connection_settings:
              |      connection_pool_size: -10
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10")))
          }
        )
      }
    }
  }

  private val mockedHttpClientsFactory: HttpClientsFactory = {
    val httpClientMock = mock[HttpClient]
    new MockHttpClientsFactoryWithFixedHttpClient(httpClientMock)
  }
}
