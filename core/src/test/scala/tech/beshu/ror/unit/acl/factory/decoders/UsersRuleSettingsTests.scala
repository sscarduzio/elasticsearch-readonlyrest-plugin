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
import tech.beshu.ror.accesscontrol.blocks.rules.UsersRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.CaseMappingEquality._
import tech.beshu.ror.utils.TestsUtils

class UsersRuleSettingsTests extends BaseRuleSettingsDecoderTest[UsersRule] {

  "A UsersRule" should {
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
              |    users: "user1"
              |
              |""".stripMargin,
          assertion = rule => {
            val userIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]] = NonEmptySet.one(AlreadyResolved(User.Id("user1".nonempty).nel))
            rule.settings.userIds should be(userIds)
          }
        )
      }
      "only one user is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    users: "@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds.length shouldBe 1
            rule.settings.userIds.head shouldBe a [ToBeResolved[_]]
          }
        )
      }
      "several users are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    users: ["user1", "user2"]
              |
              |""".stripMargin,
          assertion = rule => {
            val userIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]] =
              NonEmptySet.of(AlreadyResolved(User.Id("user1".nonempty).nel), AlreadyResolved(User.Id("user2".nonempty).nel))
            rule.settings.userIds should be(userIds)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no user is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    users:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """users: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
