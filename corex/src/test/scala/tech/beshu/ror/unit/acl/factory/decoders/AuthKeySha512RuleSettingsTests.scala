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

import tech.beshu.ror.acl.blocks.rules.AuthKeySha512Rule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.Secret

class AuthKeySha512RuleSettingsTests extends BaseRuleSettingsDecoderTest[AuthKeySha512Rule] {

  "An AuthKeySha512Rule" should {
    "be able to be loaded from config" when {
      "only one SHA512 auth key is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha512: "688aced3690d88ae780979585038f4c5072a8f7fce6a8edfdc8e63a6fbf1d5d4c420f60352f90e25d7932234eef8cf17c9d8c9e52dbdce4bad62477d396aa187"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.authKey should be(Secret("688aced3690d88ae780979585038f4c5072a8f7fce6a8edfdc8e63a6fbf1d5d4c420f60352f90e25d7932234eef8cf17c9d8c9e52dbdce4bad62477d396aa187"))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no SHA512 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha512:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """auth_key_sha512: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
