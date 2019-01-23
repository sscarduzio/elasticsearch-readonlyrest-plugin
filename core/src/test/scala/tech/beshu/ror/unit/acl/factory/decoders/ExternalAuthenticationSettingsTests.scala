package tech.beshu.ror.unit.acl.factory.decoders

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthenticationService, ExternalAuthenticationService, HttpExternalAuthenticationService}
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.{DefinitionsLevelCreationError, RulesLevelCreationError, UnparsableYamlContent}
import tech.beshu.ror.mocks.MockHttpClientsFactoryWithFixedHttpClient

class ExternalAuthenticationSettingsTests
  extends RuleSettingsDecoderTest[ExternalAuthenticationRule] with MockFactory {

  "An ExternalAuthenticationRule" should {
    "be able to be loaded from config" when {
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
            rule.settings.service shouldBe a[CachingExternalAuthenticationService]
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
            rule.settings.service shouldBe a[HttpExternalAuthenticationService]
          }
        )
      }
      "authentication service definition is declared with all required fields" in {
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
            rule.settings.service shouldBe a[HttpExternalAuthenticationService]
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
            rule.settings.service shouldBe a[CachingExternalAuthenticationService]
          }
        )
      }
    }
    "not be able to be loaded from config" when {
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
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """external_authentication:
                |  service: ext1
                |""".stripMargin
            )))
          }
        )
      }
      "extended version of rule definition cannot be mixes with simple one" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    external_authentication: "ext1"
              |      cache_ttl_in_sec: 60
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
            errors.head shouldBe an [UnparsableYamlContent]
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
            errors.head should be(RulesLevelCreationError(MalformedValue(
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
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- authentication_endpoint: http://localhost:8080/auth1
                |  success_status_code: !!int '2e2'
                |  cache_ttl_in_sec: !!int '6e1'
                |""".stripMargin
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
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- name: ext1
                |  success_status_code: !!int '2e2'
                |  cache_ttl_in_sec: !!int '6e1'
                |""".stripMargin
            )))
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
