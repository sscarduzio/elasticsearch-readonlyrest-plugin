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

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, JwtDef, ProxyAuth, RorKbnDef}
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.{AuthKeyRule, AuthKeySha1Rule}
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{User, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, ImpersonationDefinitionsDecoderCreator}
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ImpersonationSettingsTests extends BaseDecoderTest(
  new ImpersonationDefinitionsDecoderCreator(
    UserIdEq.caseSensitive,
    Definitions[ExternalAuthenticationService](Nil),
    Definitions[ProxyAuth](Nil),
    Definitions[JwtDef](Nil),
    Definitions[LdapService](Nil),
    Definitions[RorKbnDef](Nil),
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
              impersonator.usernames should be(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern("admin"))))
              impersonator.impersonatedUsers should be(UniqueNonEmptyList.of(User.Id("*")))
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
            impersonator1.usernames should be(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern("admin"))))
            impersonator1.impersonatedUsers should be(UniqueNonEmptyList.of(User.Id("*")))
            impersonator1.authenticationRule shouldBe a[AuthKeySha1Rule]

            val impersonator2 = definitions.items.tail.head
            impersonator2.usernames should be(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern("admin2"))))
            impersonator2.impersonatedUsers should be(UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2")))
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
            error should be(DefinitionsLevelCreationError(Message("Non empty list of user IDs are required")))
          }
        )
      }
    }
  }
}
