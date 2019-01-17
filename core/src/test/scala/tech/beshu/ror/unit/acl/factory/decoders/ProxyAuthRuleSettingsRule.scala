package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.rules.ProxyAuthRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.User
import tech.beshu.ror.commons.orders._

class ProxyAuthRuleSettingsRule extends RuleSettingsDecoderTest[ProxyAuthRule] {

  "A ProxyAuthRule" should {
    "be able to read from config" when {
      "only one user is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |      users: user1
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds should be(NonEmptySet.one(User.Id("user1")))
            rule.settings.userHeaderName should be(Header.Name("X-Forwarded-User"))
          }
        )
      }
      "only one user is defined with overwritten user header name" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |      proxy_auth_config: "proxy1"
              |      users: user1
              |
              |  proxy_auth_configs:
              |
              |  - name: "proxy1"
              |    user_id_header: "X-Auth-Token"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds should be(NonEmptySet.one(User.Id("user1")))
            rule.settings.userHeaderName should be(Header.Name("X-Auth-Token"))
          }
        )
      }
      "several user are defined with overwritten user header name" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |      proxy_auth_config: "proxy1"
              |      users: [user1, user2]
              |
              |  proxy_auth_configs:
              |
              |  - name: "proxy1"
              |    user_id_header: "X-Auth-Token"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds should be(NonEmptySet.of(User.Id("user1"), User.Id("user2")))
            rule.settings.userHeaderName should be(Header.Name("X-Auth-Token"))
          }
        )
      }
    }
    "not be able to read from config" when {
      "no proxy_auth data is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "proxy_auth" : null
                |}]
              """.stripMargin))))
          }
        )
      }
      "proxy auth is defined, but without users field" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |      proxy_auth_config: "proxy1"
              |
              |  proxy_auth_configs:
              |
              |  - name: "proxy1"
              |    user_id_header: "X-Auth-Token"
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              jsonFrom("""[
                         |  {
                         |    "proxy_auth" : {
                         |      "proxy_auth_config" : "proxy1"
                         |    }
                         |  }
                         |]""".stripMargin)
            )))
          }
        )
      }
      "one user is defined, but there is no definition for proxy with given name" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |      proxy_auth_config: "proxy1"
              |      users: user1
              |
              |  proxy_auth_configs:
              |
              |  - name: "proxy3"
              |    user_id_header: "X-Auth-Token"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find proxy auth with name: proxy1")))
          }
        )
      }
    }
  }
}
