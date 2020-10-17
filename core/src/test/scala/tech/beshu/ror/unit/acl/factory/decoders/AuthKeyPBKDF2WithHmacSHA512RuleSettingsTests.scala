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

import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyPBKDF2WithHmacSHA512Rule
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.utils.TestsUtils._

class AuthKeyPBKDF2WithHmacSHA512RuleSettingsTests
  extends BaseRuleSettingsDecoderTest[AuthKeyPBKDF2WithHmacSHA512Rule] {

  "An AuthKeyPBKDF2WithHmacSHA512Rule" should {
    "be able to be loaded from config" when {
      "PBKDF2 auth key is defined (all hashed syntax)" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_pbkdf2: "KhIxF5EEYkH5GPX51zTRIR4cHqhpRVALSmTaWE18mZEL2KqCkRMeMU4GR848mGq4SDtNvsybtJ/sZBuX6oFaSg=="
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.credentials should be {
              HashedCredentials.HashedUserAndPassword("KhIxF5EEYkH5GPX51zTRIR4cHqhpRVALSmTaWE18mZEL2KqCkRMeMU4GR848mGq4SDtNvsybtJ/sZBuX6oFaSg==".nonempty)
            }
          }
        )
      }
      "PBKDF2 auth key is defined (password hashed syntax)" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_pbkdf2: "user1:JltDNAoXNtc7MIBs2FYlW0o1f815ucj+bel3drdAk2yOufg2PNfQ51qr0EQ6RSkojw/DzrDLFDeXONumzwKjOA=="
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.credentials should be {
              HashedCredentials.HashedOnlyPassword(User.Id("user1".nonempty), "JltDNAoXNtc7MIBs2FYlW0o1f815ucj+bel3drdAk2yOufg2PNfQ51qr0EQ6RSkojw/DzrDLFDeXONumzwKjOA==".nonempty)
            }
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no PBKDF2 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_pbkdf2:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """auth_key_pbkdf2: null
                |""".stripMargin
            )))
          }
        )
      }
      "PBKDF2 auth key is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_pbkdf2: ""
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """auth_key_pbkdf2: ""
                |""".stripMargin
            )))
          }
        )
      }
      "PBKDF2 auth user part is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_pbkdf2: ":JltDNAoXNtc7MIBs2FYlW0o1f815ucj+bel3drdAk2yOufg2PNfQ51qr0EQ6RSkojw/DzrDLFDeXONumzwKjOA=="
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
      "PBKDF2 auth secret part is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_pbkdf2: "user1:"
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
