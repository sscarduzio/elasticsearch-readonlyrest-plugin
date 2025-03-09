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
package tech.beshu.ror.unit.acl.factory.decoders.definitions
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef.ImpersonatedUsers
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ProxyAuth}
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{AuthKeyRule, AuthKeySha1Rule}
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, RorConfigurationIndex, User, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, ImpersonationDefinitionsDecoderCreator}
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ImpersonationSettingsTests extends BaseDecoderTest(
  new ImpersonationDefinitionsDecoderCreator(
    GlobalSettings(
      showBasicAuthPrompt = true,
      forbiddenRequestMessage = "forbidden",
      flsEngine = FlsEngine.ES,
      configurationIndex = RorConfigurationIndex(fullIndexName(".readonlyrest")),
      userIdCaseSensitivity = CaseSensitivity.Enabled
    ),
    Definitions[ExternalAuthenticationService](Nil),
    Definitions[ProxyAuth](Nil),
    Definitions[LdapService](Nil),
    NoOpMocksProvider
  ).create
) {

  "An impersonation definition" should {
    "be able to be loaded from config" when {
      "one impersonator is defined" which {
        "using auth key as authentication method" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 |impersonation:
                 | - impersonator: admin
                 |   auth_key: admin:pass
                 |   users: ["*"]
           """.stripMargin,
            assertion = { definitions =>
              definitions.items should have size 1
              val impersonator = definitions.items.head
              impersonator.impersonatorUsernames should be(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("admin")))))
              impersonator.impersonatedUsers should be(ImpersonatedUsers(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("*"))))))
              impersonator.authenticationRule shouldBe a[AuthKeyRule]
            }
          )
        }
      }
      "two impersonators are defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |impersonation:
               | - impersonator: admin
               |   auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |   users: ["*"]
               | - impersonator: admin2
               |   auth_key: admin2:pass
               |   users: ["user1", "user2"]
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 2

            val impersonator1 = definitions.items.head
            impersonator1.impersonatorUsernames should be(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("admin")))))
            impersonator1.impersonatedUsers should be(ImpersonatedUsers(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("*"))))))
            impersonator1.authenticationRule shouldBe a[AuthKeySha1Rule]

            val impersonator2 = definitions.items.tail.head
            impersonator2.impersonatorUsernames should be(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("admin2")))))
            impersonator2.impersonatedUsers should be(ImpersonatedUsers(UserIdPatterns(
              UniqueNonEmptyList.of(UserIdPattern(User.Id("user1")), UserIdPattern(User.Id("user2")))
            )))
            impersonator2.authenticationRule shouldBe a[AuthKeyRule]
          }
        )
      }
    }
    "be empty" when {
      "there is no impersonation section" in {
        assertDecodingSuccess(
          yaml = "",
          assertion = { definitions =>
            definitions.items should have size 0
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "impersonation section is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |impersonation:
              """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message("impersonation declared, but no definition found")))
          }
        )
      }
      "there is no impersonator key" in {
        assertDecodingFailure(
          yaml =
            s"""
               |impersonation:
               | - auth_key: admin:pass
               |   users: ["*"]
           """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message("Impersonation definition malformed")))
          }
        )
      }
      "there is no authentication method defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |impersonation:
               | - impersonator: admin
               |   users: ["*"]
           """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message("No authentication method defined for [admin]")))
          }
        )
      }
      "unknown authentication method is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |impersonation:
               | - impersonator: admin
               |   unknown_auth: admin:pass
               |   users: ["*"]
           """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message("Only an authentication rule can be used in context of 'impersonator' definition")))
          }
        )
      }
      "there is no users key" in {
        assertDecodingFailure(
          yaml =
            s"""
               |impersonation:
               | - impersonator: admin
               |   auth_key: admin:pass
           """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message("Impersonation definition malformed")))
          }
        )
      }
      "users set is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |impersonation:
               | - impersonator: admin
               |   auth_key: admin:pass
               |   users: []
           """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message("Non empty list of user ID patterns are required")))
          }
        )
      }
      "impersonator and user to be impersonated occurs in the impersonator and users sections" in {
        assertDecodingFailure(
          yaml =
            s"""
               |impersonation:
               | - impersonator: ["admin1"]
               |   auth_key: admin1:pass
               |   users: ["admin1"]
           """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message(
              "Each of the given users [admin1] should be either impersonator or a user to be impersonated"
            )))
          }
        )
      }
    }
  }
}
