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

import cats.Order
import cats.data.NonEmptySet
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.UsersRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.*
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, IndexName, RorSettingsIndex, User}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*

class UsersRuleSettingsTests extends BaseRuleSettingsDecoderTest[UsersRule] {

  private implicit val orderUserId: Order[User.Id] = userIdOrder(
    GlobalSettings(
      showBasicAuthPrompt = true,
      forbiddenRequestMessage = "Forbidden",
      flsEngine = FlsEngine.default,
      settingsIndex = RorSettingsIndex(IndexName.Full(".readonlyrest")),
      userIdCaseSensitivity = CaseSensitivity.Enabled,
      usersDefinitionDuplicateUsernamesValidationEnabled = true
    ),
  )

  "A UsersRule" should {
    "be able to be loaded from settings" when {
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
            val userIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]] =
              NonEmptySet.one(AlreadyResolved(User.Id("user1").nel): RuntimeMultiResolvableVariable[User.Id])
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
              NonEmptySet.of(AlreadyResolved(User.Id("user1").nel), AlreadyResolved(User.Id("user2").nel))
            rule.settings.userIds should be(userIds)
          }
        )
      }
    }
    "not be able to be loaded from settings" when {
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
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """users: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
