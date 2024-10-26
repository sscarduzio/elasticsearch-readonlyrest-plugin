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

import eu.timepit.refined.auto.*
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeySha1Rule
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class AuthKeySha1RuleSettingsTests extends BaseRuleSettingsDecoderTest[AuthKeySha1Rule] {

  "An AuthKeySha1Rule" should {
    "be able to be loaded from config" when {
      "SHA1 auth key is defined (all hashed syntax)" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.credentials should be {
              HashedCredentials.HashedUserAndPassword("d27aaf7fa3c1603948bb29b7339f2559dc02019a")
            }
          }
        )
      }
      "SHA1 auth key is defined (password hashed syntax)" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "user1:d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.credentials should be {
              HashedCredentials.HashedOnlyPassword(User.Id("user1"), "d27aaf7fa3c1603948bb29b7339f2559dc02019a")
            }
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no SHA1 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """auth_key_sha1: null
                |""".stripMargin
            )))
          }
        )
      }
      "SHA1 auth key is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: ""
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """auth_key_sha1: ""
                |""".stripMargin
            )))
          }
        )
      }
      "SHA1 auth user part is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: ":d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message(
              "Auth key rule credentials malformed (expected two non-empty values separated with colon)"
            )))
          }
        )
      }
      "SHA1 auth secret part is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "user1:"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message(
              "Auth key rule credentials malformed (expected two non-empty values separated with colon)"
            )))
          }
        )
      }
    }
  }
}
