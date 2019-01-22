package tech.beshu.ror.unit.acl.factory.decoders

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthenticationService, ExternalAuthenticationService}
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
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
            rule.settings.service.name should be(ExternalAuthenticationService.Name("ext1"))
            rule.settings.service shouldBe a[CachingExternalAuthenticationService]
          }
        )
      }
    }
    "not be able to be loaded from config" when {
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
              """readonlyrest:
                |  access_control_rules:
                |  - external_authentication: null
                |  external_authentication_service_configs:
                |  - name: ext1
                |    authentication_endpoint: http://localhost:8080/auth1
                |    success_status_code: !!int '2e2'
                |    cache_ttl_in_sec: !!int '6e1'
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
