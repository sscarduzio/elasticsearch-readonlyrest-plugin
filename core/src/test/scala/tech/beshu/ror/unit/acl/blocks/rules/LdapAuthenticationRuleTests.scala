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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthenticationService, LdapService}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.Rejected.Cause.{ImpersonationNotAllowed, ImpersonationNotSupported}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.LoggedUser._
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain.{Credentials, Group, PlainTextSecret, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq

class LdapAuthenticationRuleTests extends AnyWordSpec with MockFactory {

  "An LdapAuthenticationRule" should {
    "match" when {
      "LDAP service authenticates user" in {
        val requestContext = MockRequestContext.indices.copy(headers = Set(basicAuthHeader("admin:pass")))
        val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

        val service = mock[LdapAuthenticationService]
        (service.authenticate _).expects(User.Id("admin"), PlainTextSecret("pass")).returning(Task.now(true))

        val rule = new LdapAuthenticationRule(
          LdapAuthenticationRule.Settings(service),
          Impersonation.Disabled,
          UserIdEq.caseSensitive
        )
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
          CurrentUserMetadataRequestBlockContext(
            requestContext,
            UserMetadata.from(requestContext).withLoggedUser(DirectlyLoggedUser(Id("admin"))),
            Set.empty,
            List.empty)
        ))
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider has a given user with allowed groups" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

            val service = mock[LdapAuthenticationService]
            (service.id _).expects().returning(LdapService.Name("ldap1"))

            val rule = new LdapAuthenticationRule(
              LdapAuthenticationRule.Settings(service),
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsers = NonEmptyList.of(User.Id("user1"))
                )),
                mocksProvider = mocksProviderFrom(Map(
                  LdapService.Name("ldap1") -> Set((User.Id("user1"), Set(Group("g1"))))
                ))
              )),
              UserIdEq.caseSensitive
            )
            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
              CurrentUserMetadataRequestBlockContext(
                requestContext,
                UserMetadata.from(requestContext).withLoggedUser(ImpersonatedUser(Id("user1"), Id("admin"))),
                Set.empty,
                List.empty)
            ))
          }
        }
      }
    }
    "not match" when {
      "LDAP service doesn't authenticate user" in {
        val requestContext = MockRequestContext.indices.copy(headers = Set(basicAuthHeader("admin:pass")))
        val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

        val service = mock[LdapAuthenticationService]
        (service.authenticate _).expects(User.Id("admin"), PlainTextSecret("pass")).returning(Task.now(false))

        val rule = new LdapAuthenticationRule(
          LdapAuthenticationRule.Settings(service),
          Impersonation.Disabled,
          UserIdEq.caseSensitive
        )
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
      "there is no basic auth header" in {
        val requestContext = MockRequestContext.indices
        val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)
        val service = mock[LdapAuthenticationService]

        val rule = new LdapAuthenticationRule(
          LdapAuthenticationRule.Settings(service),
          Impersonation.Disabled,
          UserIdEq.caseSensitive
        )
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
      "LDAP service fails" in {
        val requestContext = MockRequestContext.indices.copy(headers = Set(basicAuthHeader("admin:pass")))
        val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

        val service = mock[LdapAuthenticationService]
        (service.authenticate _).expects(User.Id("admin"), PlainTextSecret("pass")).returning(Task.raiseError(TestException("Cannot reach LDAP")))

        val rule = new LdapAuthenticationRule(
          LdapAuthenticationRule.Settings(service),
          Impersonation.Disabled,
          UserIdEq.caseSensitive
        )
        val thrown = the[TestException] thrownBy rule.check(blockContext).runSyncUnsafe()
        thrown.getMessage should equal("Cannot reach LDAP")
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)
            val service = mock[LdapAuthenticationService]

            val rule = new LdapAuthenticationRule(
              LdapAuthenticationRule.Settings(service),
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("different_password")),
                  impersonatedUsers = NonEmptyList.of(User.Id("user1"))
                )),
                mocksProvider = mocksProviderFrom(Map(
                  LdapService.Name("ldap1") -> Set((User.Id("user1"), Set(Group("g1"))))
                ))
              )),
              UserIdEq.caseSensitive
            )
            
            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected(ImpersonationNotAllowed))
          }
          "admin cannot impersonate the given user" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)
            val service = mock[LdapAuthenticationService]

            val rule = new LdapAuthenticationRule(
              LdapAuthenticationRule.Settings(service),
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsers = NonEmptyList.of(User.Id("user2"))
                )),
                mocksProvider = mocksProviderFrom(Map(
                  LdapService.Name("ldap1") -> Set((User.Id("user1"), Set(Group("g1"))))
                ))
              )),
              UserIdEq.caseSensitive
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected(ImpersonationNotAllowed))
          }
          "mocks provider doesn't have the given user" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)
            val service = mock[LdapAuthenticationService]
            (service.id _).expects().returning(LdapService.Name("ldap1"))

            val rule = new LdapAuthenticationRule(
              LdapAuthenticationRule.Settings(service),
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsers = NonEmptyList.of(User.Id("user1"))
                )),
                mocksProvider = mocksProviderFrom(Map(
                  LdapService.Name("ldap1") -> Set((User.Id("user2"), Set(Group("g1"))))
                ))
              )),
              UserIdEq.caseSensitive
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
          }
          "mocks provider is unavailable" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)
            val service = mock[LdapAuthenticationService]
            (service.id _).expects().returning(LdapService.Name("ldap1"))

            val rule = new LdapAuthenticationRule(
              LdapAuthenticationRule.Settings(service),
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsers = NonEmptyList.of(User.Id("user1"))
                )),
                mocksProvider = NoOpMocksProvider
              )),
              UserIdEq.caseSensitive
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected(ImpersonationNotSupported))
          }
        }
        "impersonation is disabled" when {
          "admin is trying to impersonate user" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)
            val service = mock[LdapAuthenticationService]
            (service.authenticate _).expects(User.Id("admin"), PlainTextSecret("pass")).returning(Task.now(false))

            val rule = new LdapAuthenticationRule(
              LdapAuthenticationRule.Settings(service),
              Impersonation.Disabled,
              UserIdEq.caseSensitive
            )
            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
          }
        }
      }
    }
  }

  private sealed case class TestException(message: String) extends Exception(message)
}
