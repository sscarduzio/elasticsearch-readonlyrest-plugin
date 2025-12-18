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
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.{BasicAuthHttpExternalAuthenticationService, CacheableExternalAuthenticationServiceDecorator, ExternalAuthenticationService}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthenticationRule
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.mocks.MockHttpClientsFactoryWithFixedHttpClient
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class ExternalAuthenticationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[ExternalAuthenticationRule] with MockFactory {

  "An ExternalAuthenticationRule" should {
    "be able to be loaded from settings" when {
      "one authentication service is declared" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.service.id should be(ExternalAuthenticationService.Name("ext1"))
            rule.settings.service shouldBe a[CacheableExternalAuthenticationServiceDecorator]
          }
        )
      }
      "two authentication services are declared" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: ext2
              |
              |  external_authentication_service_configs:
              |
              |  - name: ext1
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |
              |  - name: "ext2"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.service.id should be(ExternalAuthenticationService.Name("ext2"))
            rule.settings.service shouldBe a[BasicAuthHttpExternalAuthenticationService]
          }
        )
      }
      "authentication service definition is declared only using required fields" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.service.id should be(ExternalAuthenticationService.Name("ext1"))
            rule.settings.service shouldBe a[BasicAuthHttpExternalAuthenticationService]
          }
        )
      }
      "authentication service definition is declared only all available fields and default http client settings" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 204
              |    cache_ttl_in_sec: 200
              |    validate: true
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.service.id should be(ExternalAuthenticationService.Name("ext1"))
            rule.settings.service shouldBe a[CacheableExternalAuthenticationServiceDecorator]
          }
        )
      }
      "authentication service definition is declared only all available fields and custom http client settings" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 204
              |    cache_ttl_in_sec: 200
              |    http_connection_settings:
              |      connection_timeout_in_sec: 1
              |      connection_request_timeout_in_sec: 10
              |      connection_pool_size: 30
              |      validate: true
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.service.id should be(ExternalAuthenticationService.Name("ext1"))
            rule.settings.service shouldBe a[CacheableExternalAuthenticationServiceDecorator]
          }
        )
      }
      "authentication rule can have caching declared at rule level" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication:
              |      service: "ext1"
              |      cache_ttl_in_sec: 60
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.service.id should be(ExternalAuthenticationService.Name("ext1"))
            rule.settings.service shouldBe a[CacheableExternalAuthenticationServiceDecorator]
          }
        )
      }
    }
    "not be able to be loaded from settings" when {
      "extended version of rule definition doesn't declare cache TTL" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication:
              |      service: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """external_authentication:
                |  service: "ext1"
                |""".stripMargin
            )))
          }
        )
      }
      "no authentication service is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication:
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """external_authentication: null
                |""".stripMargin
            )))
          }
        )
      }
      "no authentication service with given name is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: ext2
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find external authentication service with name: ext2")))
          }
        )
      }
      "authentication service doesn't have a name" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: ext1
              |
              |  external_authentication_service_configs:
              |
              |  - authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- authentication_endpoint: "http://localhost:8080/auth1"
                |  success_status_code: 200
                |  cache_ttl_in_sec: 60
                |""".stripMargin
            )))
          }
        )
      }
      "names of authentication services are not unique" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: ext1
              |
              |  external_authentication_service_configs:
              |
              |  - name: ext1
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 200
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "external_authentication_service_configs definitions must have unique identifiers. Duplicates: ext1"
            )))
          }
        )
      }
      "authentication service doesn't have an endpoint defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: ext1
              |
              |  external_authentication_service_configs:
              |
              |  - name: ext1
              |    success_status_code: 200
              |    cache_ttl_in_sec: 60
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- name: "ext1"
                |  success_status_code: 200
                |  cache_ttl_in_sec: 60
                |""".stripMargin
            )))
          }
        )
      }
      "authentication service endpoint url is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://sth@{user}/test"
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("""Cannot convert value 'http://sth@{user}/test' to url""")))
          }
        )
      }
      "authentication service success http code is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: success
              |    cache_ttl_in_sec: 60
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- name: "ext1"
                |  authentication_endpoint: "http://localhost:8080/auth1"
                |  success_status_code: "success"
                |  cache_ttl_in_sec: 60
                |""".stripMargin
            )))
          }
        )
      }
      "authentication service TTL value is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    success_status_code: 202
              |    cache_ttl_in_sec: one_hundred
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              """Cannot convert value '"one_hundred"' to duration"""
            )))
          }
        )
      }
      "authentication service TTL value is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
              |    cache_ttl_in_sec: -100
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -100 seconds")))
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
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
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
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
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
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
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
              |    external_authentication: "ext1"
              |
              |  external_authentication_service_configs:
              |
              |  - name: "ext1"
              |    authentication_endpoint: "http://localhost:8080/auth1"
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
