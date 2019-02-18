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

import tech.beshu.ror.acl.blocks.rules.AuthKeySha256Rule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.domain.Secret

class AuthKeySha256RuleSettingsTests extends BaseRuleSettingsDecoderTest[AuthKeySha256Rule] {

  "An AuthKeySha256Rule" should {
    "be able to be loaded from config" when {
      "only one SHA256 auth key is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256: "bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.authKey should be(Secret("bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no SHA256 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """auth_key_sha256: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
