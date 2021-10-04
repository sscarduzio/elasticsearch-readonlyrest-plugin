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
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthenticationService, LdapAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.ImpersonationSettings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.{AssertionType, BlockContextAssertion, _}
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class LdapAuthRuleTests
  extends AnyWordSpec
    with Inside
    with MockFactory
    with BlockContextAssertion {

  "An LdapAuthRule" should {
    "match" when {
      "user can be authenticated and has at least one LDAP group which is permitted" in {
        assertMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(Group("g1")))),
            UniqueNonEmptyList.of(Group("g1"), Group("g2")),
            UniqueNonEmptyList.of(Group("g1"), Group("g2"))
          ),
          basicHeader = basicAuthHeader("user1:pass")
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            user = User.Id("user1"),
            group = groupFrom("g1"),
            availableGroups = UniqueList.of(groupFrom("g1"))
          )
        )
      }
    }
    "not match" when {
      "basic auth header is not passed" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mock[LdapAuthorizationService],
            UniqueNonEmptyList.of(Group("g1"), Group("g2")),
            UniqueNonEmptyList.of(Group("g1"), Group("g2"))
          ),
          basicHeader = None
        )
      }
      "user cannot be authenticated" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(false))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mock[LdapAuthorizationService],
            UniqueNonEmptyList.of(Group("g1"), Group("g2")),
            UniqueNonEmptyList.of(Group("g1"), Group("g2"))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass"))
        )
      }
      "user don't have permitted group" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.empty)),
            UniqueNonEmptyList.of(Group("g1"), Group("g2")),
            UniqueNonEmptyList.of(Group("g1"), Group("g2"))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass"))
        )
      }
      "LDAP authentication fails" in {
        assertRuleThrown(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.raiseError(TestException("authentication failure")))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mock[LdapAuthorizationService],
            UniqueNonEmptyList.of(Group("g1"), Group("g2")),
            UniqueNonEmptyList.of(Group("g1"), Group("g2"))
          ),
          basicHeader = basicAuthHeader("user1:pass"),
          TestException("authentication failure")
        )
      }
      "LDAP authorization fails" in {
        assertRuleThrown(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mockLdapAuthorizationService(User.Id("user1"), Task.raiseError(TestException("authorization failure"))),
            UniqueNonEmptyList.of(Group("g1"), Group("g2")),
            UniqueNonEmptyList.of(Group("g1"), Group("g2"))
          ),
          basicHeader = basicAuthHeader("user1:pass"),
          TestException("authorization failure")
        )
      }
    }
  }

  private def assertMatchRule(authenticationSettings: LdapAuthenticationRule.Settings,
                              authorizationSettings: LdapAuthorizationRule.Settings,
                              basicHeader: Header)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(authenticationSettings, authorizationSettings, Some(basicHeader), AssertionType.RuleFulfilled(blockContextAssertion))

  private def assertNotMatchRule(authenticationSettings: LdapAuthenticationRule.Settings,
                                 authorizationSettings: LdapAuthorizationRule.Settings,
                                 basicHeader: Option[Header]): Unit =
    assertRule(authenticationSettings, authorizationSettings, basicHeader, AssertionType.RuleRejected)

  private def assertRuleThrown(authenticationSettings: LdapAuthenticationRule.Settings,
                               authorizationSettings: LdapAuthorizationRule.Settings,
                               basicHeader: Header,
                               exception: Throwable): Unit =
    assertRule(authenticationSettings, authorizationSettings, Some(basicHeader), AssertionType.RuleThrownException(exception))

  private def assertRule(authenticationSettings: LdapAuthenticationRule.Settings,
                         authorizationSettings: LdapAuthorizationRule.Settings,
                         basicHeader: Option[Header],
                         assertionType: AssertionType): Unit = {
    val rule = new LdapAuthRule(
      authentication = new LdapAuthenticationRule(
        authenticationSettings,
        ImpersonationSettings(List.empty, NoOpMocksProvider),
        UserIdEq.caseSensitive
      ),
      authorization = new LdapAuthorizationRule(authorizationSettings, NoOpMocksProvider, UserIdEq.caseSensitive),
      caseMappingEquality = UserIdEq.caseSensitive,
    )
    val requestContext = MockRequestContext.indices.copy(headers = basicHeader.toSet)
    val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty, Set.empty, Set.empty)
    val result = Try(rule.check(blockContext).runSyncUnsafe(1 second))
    assertionType match {
      case AssertionType.RuleFulfilled(blockContextAssertion) =>
        inside(result) { case Success(Fulfilled(outBlockContext)) =>
          blockContextAssertion(outBlockContext)
        }
      case AssertionType.RuleRejected =>
        result should be(Success(Rejected()))
      case AssertionType.RuleThrownException(ex) =>
        result should be(Failure(ex))
    }
  }

  private def mockLdapAuthenticationService(user: User.Id, secret: PlainTextSecret, result: Task[Boolean]) = {
    val service = mock[LdapAuthenticationService]
    (service.authenticate _).expects(user, secret).returning(result)
    service
  }

  private def mockLdapAuthorizationService(user: User.Id, result: Task[UniqueList[Group]]) = {
    val service = mock[LdapAuthorizationService]
    (service.groupsOf _).expects(user).returning(result)
    service
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: Group,
                                                 availableGroups: UniqueList[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }

  private sealed case class TestException(message: String) extends Exception(message)
}