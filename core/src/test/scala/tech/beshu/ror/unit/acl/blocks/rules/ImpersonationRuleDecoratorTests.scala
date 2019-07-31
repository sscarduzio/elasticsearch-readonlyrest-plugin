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

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext
import tech.beshu.ror.acl.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.acl.blocks.rules.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.{AuthKeyRule, AuthKeySha1Rule, BasicAuthenticationRule}
import tech.beshu.ror.acl.domain.LoggedUser.ImpersonatedUser
import tech.beshu.ror.acl.domain.{Credentials, Header, PlainTextSecret, User}
import tech.beshu.ror.acl.orders.userIdOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

class ImpersonationRuleDecoratorTests extends WordSpec with MockFactory with Inside with BlockContextAssertion {

  private val rule = authKeyRuleWithConfiguredImpersonation("user1", "secret")

  "An impersonation rule decorator" should {
    "skip impersonation" when {
      "no impersonation header is passed in request" in {
        val requestContext = MockRequestContext.default.copy(
          headers = Set(basicAuthHeader("admin1:pass"))
        )
        val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

        val result = rule.check(requestContext, blockContext).runSyncUnsafe()

        result should be (Rejected())
      }
    }
    "allow to impersonate user" when {
      "impersonator has proper rights, can be authenticated and underlying rule support impersonation" when {
        "admin1 is impersonator" in {
          val requestContext = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("admin1:pass"), Header(Header.Name.impersonateAs, "user1".nonempty))
          )
          val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

          val result = rule.check(requestContext, blockContext).runSyncUnsafe()

          inside(result) { case Fulfilled(newBlockContext) =>
            assertBlockContext(
              loggedUser = Some(ImpersonatedUser(User.Id("user1".nonempty), User.Id("admin1".nonempty)))
            ) {
              newBlockContext
            }
          }
        }
        "admin3 is impersonator" in {
          val requestContext = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("admin3:pass"), Header(Header.Name.impersonateAs, "user1".nonempty))
          )
          val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

          val result = rule.check(requestContext, blockContext).runSyncUnsafe()

          inside(result) { case Fulfilled(newBlockContext) =>
            assertBlockContext(
              loggedUser = Some(ImpersonatedUser(User.Id("user1".nonempty), User.Id("admin3".nonempty)))
            ) {
              newBlockContext
            }
          }
        }
      }
    }
    "not allow to impersonate user" when {
      "impersonator has no rights to do it" in {
        val requestContext = MockRequestContext.default.copy(
          headers = Set(basicAuthHeader("regularuser:pass"), Header(Header.Name.impersonateAs, "user1".nonempty))
        )
        val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

        val result = rule.check(requestContext, blockContext).runSyncUnsafe()

        result should be (Rejected(Cause.ImpersonationNotAllowed))
      }
      "impersonator has no rights to impersonate given user" in {
        val requestContext = MockRequestContext.default.copy(
          headers = Set(basicAuthHeader("admin2:pass"), Header(Header.Name.impersonateAs, "user1".nonempty))
        )
        val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

        val result = rule.check(requestContext, blockContext).runSyncUnsafe()

        result should be (Rejected(Cause.ImpersonationNotAllowed))
      }
      "impersonator authentication failed" in {
        val requestContext = MockRequestContext.default.copy(
          headers = Set(basicAuthHeader("admin1:invalid_password"), Header(Header.Name.impersonateAs, "user1".nonempty))
        )
        val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

        val result = rule.check(requestContext, blockContext).runSyncUnsafe()

        result should be (Rejected(Cause.ImpersonationNotAllowed))
      }
      "impersonation is not supported by underlying rule" in {
        val requestContext = MockRequestContext.default.copy(
          headers = Set(basicAuthHeader("admin1:pass"), Header(Header.Name.impersonateAs, "user1".nonempty))
        )
        val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

        val rule = authRuleWithImpersonation { defs =>
          new AuthKeySha1Rule(BasicAuthenticationRule.Settings(HashedCredentials.HashedUserAndPassword("xxxxxxxxxxx".nonempty)), defs)
        }
        val result = rule.check(requestContext, blockContext).runSyncUnsafe()

        result should be (Rejected(Cause.ImpersonationNotSupported))
      }
      "underlying rule returns info that given user doesn't exist" in {
        val requestContext = MockRequestContext.default.copy(
          headers = Set(basicAuthHeader("admin2:pass"), Header(Header.Name.impersonateAs, "user2".nonempty))
        )
        val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)

        val result = rule.check(requestContext, blockContext).runSyncUnsafe()

        result should be (Rejected())
      }
    }
  }

  private def authKeyRuleWithConfiguredImpersonation(user: String, password: String) = {
    authRuleWithImpersonation { defs =>
      new AuthKeyRule(
        BasicAuthenticationRule.Settings(Credentials(User.Id(user.nonempty), PlainTextSecret(password.nonempty))),
        defs
      )
    }
  }

  private def authRuleWithImpersonation(authKeyRuleCreator: List[ImpersonatorDef] => AuthenticationRule) = {
    authKeyRuleCreator {
      List(
        ImpersonatorDef(
          User.Id("admin1".nonempty),
          authKeyRule("admin1", "pass"),
          NonEmptySet.one(User.Id("*".nonempty))
        ),
        ImpersonatorDef(
          User.Id("admin2".nonempty),
          authKeyRule("admin2", "pass"),
          NonEmptySet.of(User.Id("user2".nonempty), User.Id("user3".nonempty))
        ),
        ImpersonatorDef(
          User.Id("admin3".nonempty),
          authKeyRule("admin3", "pass"),
          NonEmptySet.one(User.Id("user1".nonempty))
        )
      )
    }
  }

  private def authKeyRule(user: String, password: String) = {
    new AuthKeyRule(
      BasicAuthenticationRule.Settings(Credentials(User.Id(user.nonempty), PlainTextSecret(password.nonempty))),
      Nil
    )
  }
}
