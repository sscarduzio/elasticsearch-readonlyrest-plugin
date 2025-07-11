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
package tech.beshu.ror.unit.acl.blocks.definitions.user

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithoutGroupsMapping
import tech.beshu.ror.accesscontrol.blocks.definitions.user.UserDefinitionsValidator
import tech.beshu.ror.accesscontrol.blocks.definitions.user.UserDefinitionsValidator.ValidationError
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class UserDefinitionsValidatorTests extends AnyWordSpec with Matchers {

  "UserDefinitionsValidator" should {
    "validate user definitions successfully" when {
      "there are no duplicate usernames" in {
        val validator = new UserDefinitionsValidator(globalSettings(validationEnabled = true))

        val definitions = Definitions(
          List(
            createUserDef("user1", "user2"),
            createUserDef("user3", "user*")
          )
        )

        validator.validate(definitions) shouldBe Right(())
      }
      "there are wildcards in usernames" in {
        val validator = new UserDefinitionsValidator(globalSettings(validationEnabled = true))

        val definitions = Definitions(
          List(
            createUserDef("user1", "user*"),
            createUserDef("user*", "admin")
          )
        )

        validator.validate(definitions) shouldBe Right(())
      }
      "there are duplicate usernames but validation is disabled" in {
        val validator = new UserDefinitionsValidator(globalSettings(validationEnabled = false))

        val definitions = Definitions(
          List(
            createUserDef("user1", "user2"),
            createUserDef("user2", "user3")
          )
        )

        validator.validate(definitions) shouldBe Right(())
      }
    }

    "return validation error" when {
      "there are duplicate usernames and validation is enabled" in {
        val validator = new UserDefinitionsValidator(globalSettings(validationEnabled = true))

        val definitions = Definitions(
          List(
            createUserDef("user1", "user2"),
            createUserDef("user2", "user3")
          )
        )

        val result = validator.validate(definitions)

        result shouldBe Left(NonEmptyList.of(
          ValidationError.DuplicatedUsernameForLocalUser(userId("user2"))
        ))
      }
    }

    "return multiple validation errors" when {
      "there are multiple duplicate usernames" in {
        val validator = new UserDefinitionsValidator(globalSettings(validationEnabled = true))

        val definitions = Definitions(
          List(
            createUserDef("user1", "user2", "user3"),
            createUserDef("user2", "user4"),
            createUserDef("user3", "user5")
          )
        )

        val result = validator.validate(definitions)

        result shouldBe Left(NonEmptyList.of(
          ValidationError.DuplicatedUsernameForLocalUser(userId("user2")),
          ValidationError.DuplicatedUsernameForLocalUser(userId("user3"))
        ))
      }
    }
  }

  private def globalSettings(validationEnabled: Boolean): GlobalSettings = {
    GlobalSettings(
      showBasicAuthPrompt = false,
      forbiddenRequestMessage = "Forbidden",
      flsEngine = GlobalSettings.FlsEngine.default,
      configurationIndex = RorSettingsIndex(IndexName.Full(nes(".readonlyrest"))),
      userIdCaseSensitivity = CaseSensitivity.Enabled,
      usersDefinitionDuplicateUsernamesValidationEnabled = validationEnabled
    )
  }

  private def createUserDef(username: String, usernames: String*): UserDef = {
    UserDef(
      userIdPatterns(username, usernames: _*),
      WithoutGroupsMapping(
        new AuthKeyRule(
          BasicAuthenticationRule.Settings(Credentials(
            User.Id(NonEmptyString.unsafeFrom("example-user")),
            PlainTextSecret(NonEmptyString.unsafeFrom("example-password"))
          )),
          CaseSensitivity.Enabled,
          Impersonation.Disabled,
        ),
        UniqueNonEmptyList.of(group("example-group1", "Example Group 1"))
      )
    )
  }
}
