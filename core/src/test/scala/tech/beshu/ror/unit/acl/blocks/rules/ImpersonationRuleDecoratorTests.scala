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

import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.{AuthKeyRule, AuthKeySha1Rule, BasicAuthenticationRule}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.ImpersonatedUser
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{Credentials, Header, PlainTextSecret, User, UserIdPatterns}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ImpersonationRuleDecoratorTests extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion {
  private implicit val defaultUserIdEq: UserIdCaseMappingEquality = UserIdEq.caseSensitive

  private val rule = authKeyRuleWithConfiguredImpersonation("user1", "secret")

  "An impersonation rule decorator" should {
    "skip impersonation" when {
      "no impersonation header is passed in request" in {
        val requestContext = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("admin1:pass"))
        )
        val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

        val result = rule.check(blockContext).runSyncUnsafe()

        result should be(Rejected())
      }
    }
    "allow to impersonate user" when {
      "impersonator has proper rights, can be authenticated and underlying rule support impersonation" when {
        "admin1 is impersonator" in {
          val requestContext = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("admin1:pass"), new Header(Header.Name.impersonateAs, "user1"))
          )
          val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

          val result = rule.check(blockContext).runSyncUnsafe()

          inside(result) { case Fulfilled(newBlockContext: GeneralIndexRequestBlockContext) =>
            newBlockContext.userMetadata should be(
              UserMetadata.empty.withLoggedUser(ImpersonatedUser(User.Id("user1"), User.Id("admin1")))
            )
          }
        }
        "admin3 is impersonator" in {
          val requestContext = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("admin3:pass"), new Header(Header.Name.impersonateAs, "user1"))
          )
          val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

          val result = rule.check(blockContext).runSyncUnsafe()

          inside(result) { case Fulfilled(newBlockContext: GeneralIndexRequestBlockContext) =>
            newBlockContext.userMetadata should be(
              UserMetadata.empty.withLoggedUser(ImpersonatedUser(User.Id("user1"), User.Id("admin3")))
            )
          }
        }
      }
    }
    "not allow to impersonate user" when {
      "impersonator has no rights to do it" in {
        val requestContext = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("regularuser:pass"), new Header(Header.Name.impersonateAs, "user1"))
        )
        val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

        val result = rule.check(blockContext).runSyncUnsafe()

        result should be(Rejected(Cause.ImpersonationNotAllowed))
      }
      "impersonator has no rights to impersonate given user" in {
        val requestContext = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("admin2:pass"), new Header(Header.Name.impersonateAs, "user1"))
        )
        val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

        val result = rule.check(blockContext).runSyncUnsafe()

        result should be(Rejected(Cause.ImpersonationNotAllowed))
      }
      "impersonator authentication failed" in {
        val requestContext = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("admin1:invalid_password"), new Header(Header.Name.impersonateAs, "user1"))
        )
        val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

        val result = rule.check(blockContext).runSyncUnsafe()

        result should be(Rejected(Cause.ImpersonationNotAllowed))
      }
      "impersonation is not supported by underlying rule" in {
        val requestContext = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("admin1:pass"), new Header(Header.Name.impersonateAs, "user1"))
        )
        val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

        val rule = authRuleWithImpersonation { defs =>
          new AuthKeySha1Rule(
            settings = BasicAuthenticationRule.Settings(HashedCredentials.HashedUserAndPassword("xxxxxxxxxxx")),
            impersonators = defs,
            caseMappingEquality = UserIdEq.caseSensitive,
          )
        }
        val result = rule.check(blockContext).runSyncUnsafe()

        result should be(Rejected(Cause.ImpersonationNotSupported))
      }
      "underlying rule returns info that given user doesn't exist" in {
        val requestContext = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("admin2:pass"), new Header(Header.Name.impersonateAs, "user2"))
        )
        val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

        val result = rule.check(blockContext).runSyncUnsafe()

        result should be(Rejected())
      }
    }
  }

  private def authKeyRuleWithConfiguredImpersonation(user: String, password: String) = {
    authRuleWithImpersonation { defs =>
      new AuthKeyRule(
        BasicAuthenticationRule.Settings(Credentials(
          User.Id(NonEmptyString.unsafeFrom(user)),
          PlainTextSecret(NonEmptyString.unsafeFrom(password))
        )),
        defs,
        UserIdEq.caseSensitive
      )
    }
  }

  private def authRuleWithImpersonation(authKeyRuleCreator: List[ImpersonatorDef] => AuthenticationRule) = {
    authKeyRuleCreator {
      List(
        ImpersonatorDef(
          userIdPatterns("admin1"),
          authKeyRule("admin1", "pass"),
          UniqueNonEmptyList.of(User.Id("*"))
        ),
        ImpersonatorDef(
          userIdPatterns("admin2"),
          authKeyRule("admin2", "pass"),
          UniqueNonEmptyList.of(User.Id("user2"), User.Id("user3"))
        ),
        ImpersonatorDef(
          userIdPatterns("a*"),
          authKeyRule("admin3", "pass"),
          UniqueNonEmptyList.of(User.Id("user1"))
        )
      )
    }
  }

  private def authKeyRule(user: String, password: String) = {
    new AuthKeyRule(
      settings = BasicAuthenticationRule.Settings(Credentials(
        User.Id(NonEmptyString.unsafeFrom(user)),
        PlainTextSecret(NonEmptyString.unsafeFrom(password))
      )),
      impersonators = Nil,
      caseMappingEquality = defaultUserIdEq
    )
  }

  private def userIdPatterns(id: String, ids: String*) = {
    UserIdPatterns(
      UniqueNonEmptyList.unsafeFromList(
        (id :: ids.toList).map(str => UserIdPattern(NonEmptyString.unsafeFrom(str)))
      )
    )
  }

}
