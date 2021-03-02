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
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.ProxyAuthRule
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.CaseMappingEquality._
import tech.beshu.ror.utils.TestsUtils

class ProxyAuthRuleSettingsTests 
  extends BaseRuleSettingsDecoderTest[ProxyAuthRule] {

  "A ProxyAuthRule" should {
    "be able to be loaded from config" when {
      "only one user is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |      users: user1
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds should be(NonEmptySet.one(User.Id("user1".nonempty)))
            rule.settings.userHeaderName should be(headerNameFrom("X-Forwarded-User"))
          }
        )
      }
      "only one user is defined - concise style" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth: user1
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds should be(NonEmptySet.one(User.Id("user1".nonempty)))
            rule.settings.userHeaderName should be(headerNameFrom("X-Forwarded-User"))
          }
        )
      }
      "several users are defined - concise style" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth: ["user1", "user2"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds should be(NonEmptySet.of(User.Id("user1".nonempty), User.Id("user2".nonempty)))
            rule.settings.userHeaderName should be(headerNameFrom("X-Forwarded-User"))
          }
        )
      }
      "only one user is defined with overwritten user header name" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
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
            rule.settings.userIds should be(NonEmptySet.one(User.Id("user1".nonempty)))
            rule.settings.userHeaderName should be(headerNameFrom("X-Auth-Token"))
          }
        )
      }
      "several user are defined with overwritten user header name" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
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
            rule.settings.userIds should be(NonEmptySet.of(User.Id("user1".nonempty), User.Id("user2".nonempty)))
            rule.settings.userHeaderName should be(headerNameFrom("X-Auth-Token"))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no proxy_auth data is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    proxy_auth:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """proxy_auth: null
                |""".stripMargin)))
          }
        )
      }
      "proxy auth is defined, but without users field" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
              """proxy_auth:
                |  proxy_auth_config: "proxy1"
                |""".stripMargin
            )))
          }
        )
      }
      "one user is defined, but there is no definition for proxy with given name" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
