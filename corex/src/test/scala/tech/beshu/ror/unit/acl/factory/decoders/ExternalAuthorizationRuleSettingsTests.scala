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
package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.blocks.definitions._
import tech.beshu.ror.acl.blocks.rules.ExternalAuthorizationRule
import tech.beshu.ror.acl.blocks.rules.ExternalAuthorizationRule.Settings
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.{DefinitionsLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.mocks.MockHttpClientsFactoryWithFixedHttpClient

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
              |      groups: ["group3"]
              |      users: user1
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, permittedGroups, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service shouldBe a[HttpExternalAuthorizationService]
              permittedGroups should be(NonEmptySet.one(groupFrom("group3")))
              users should be(NonEmptySet.one(User.Id("user1")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |  - name: GroupsService2
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user2"
              |    auth_token_passed_as: HEADER
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, permittedGroups, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService2"))
              service shouldBe a[HttpExternalAuthorizationService]
              permittedGroups should be(NonEmptySet.one(groupFrom("group3")))
              users should be(NonEmptySet.of(User.Id("user1"), User.Id("user2")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, permittedGroups, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service shouldBe a[CachingExternalAuthorizationService]
              permittedGroups should be(NonEmptySet.one(groupFrom("group3")))
              users should be(NonEmptySet.one(User.Id("user1")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, permittedGroups, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service shouldBe a[HttpExternalAuthorizationService]
              permittedGroups should be(NonEmptySet.one(groupFrom("group3")))
              users should be(NonEmptySet.of(User.Id("*")))
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
              |      groups: ["group3"]
              |
              |  user_groups_providers:
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: HEADER
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |    http_method: POST
              |    default_query_parameters: query1:value1,query2:value2
              |    default_headers: header1:hValue1, header2:hValue2
              |    cache_ttl_in_sec: 100
              |    validate: false
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            inside(rule.settings) { case Settings(service, permittedGroups, users) =>
              service.id should be(ExternalAuthorizationService.Name("GroupsService1"))
              service shouldBe a[CachingExternalAuthorizationService]
              permittedGroups should be(NonEmptySet.one(groupFrom("group3")))
              users should be(NonEmptySet.of(User.Id("*")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """groups_provider_authorization:
                |  user_groups_provider: "GroupsService2"
                |  users: "user1"
                |""".stripMargin
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- groups_endpoint: "http://localhost:8080/groups"
                |  auth_token_name: "user"
                |  auth_token_passed_as: "QUERY_PARAM"
                |  response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user2"
              |    auth_token_passed_as: HEADER
              |    response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- name: "GroupsService1"
                |  auth_token_name: "user"
                |  auth_token_passed_as: "QUERY_PARAM"
                |  response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- name: "GroupsService1"
                |  groups_endpoint: "http://localhost:8080/groups"
                |  auth_token_name: "user"
                |  response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Unknown value 'BODY' of 'auth_token_passed_as' attribute")))
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
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- name: "GroupsService1"
                |  groups_endpoint: "http://localhost:8080/groups"
                |  auth_token_name: "user"
                |  auth_token_passed_as: "QUERY_PARAM"
                |""".stripMargin
            )))
          }
        )
      }
      "authorization service groups JSON path is malformed" in {
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
              |    response_groups_json_path: "$..groups[?.name)].name_malformed"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot compile '$..groups[?.name)].name_malformed' to JSON path")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- name: "GroupsService1"
                |  groups_endpoint: "http://localhost:8080/groups"
                |  auth_token_passed_as: "QUERY_PARAM"
                |  response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            inside(errors.head) { case DefinitionsLevelCreationError(MalformedValue(msg)) =>
              msg should include ("name: \"GroupsService1\"")
              msg should include ("auth_token_name: \"\"")
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |    cache_ttl_in_sec: -120
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive durations allowed. Found: -120 seconds")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |    http_method: DELETE
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Unknown value 'DELETE' of 'http_method' attribute")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |    default_query_parameters: "query:value:123;query1:12345"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot parse pairs: query:value:123;query1:12345")))
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
              |    response_groups_json_path: "$..groups[?(@.name)].name"
              |    default_headers: "header1:value:123;header2:12345"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot parse pairs: header1:value:123;header2:12345")))
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
