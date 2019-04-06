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

import cats.data._
import monix.eval.Task
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.{BlockContext, RequestContextInitiatedBlockContext}
import tech.beshu.ror.acl.blocks.rules.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils
import tech.beshu.ror.utils.TestsUtils.{AssertionType, BlockContextAssertion}
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.acl.blocks.definitions.ldap.{LdapAuthenticationService, LdapAuthorizationService}
import tech.beshu.ror.acl.orders._

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.language.postfixOps
import TestsUtils._

class LdapAuthRuleTests
  extends WordSpec
    with Inside
    with MockFactory
    with BlockContextAssertion {

  "An LdapAuthRule" should {
    "match" when {
      "user can be authenticated and has at least one LDAP group which is permitted" in {
        assertMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), Secret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mockLdapAuthorizationService(User.Id("user1"), Task.now(Set(Group("g1".nonempty)))),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty)),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty))
          ),
          basicHeader = basicAuthHeader("user1:pass")
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            user = User.Id("user1"),
            group = groupFrom("g1"),
            availableGroups = Set(groupFrom("g1"))
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
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty)),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty))
          ),
          basicHeader = None
        )
      }
      "user cannot be authenticated" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), Secret("pass"), Task.now(false))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mock[LdapAuthorizationService],
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty)),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass"))
        )
      }
      "user don't have permitted group" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), Secret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mockLdapAuthorizationService(User.Id("user1"), Task.now(Set.empty)),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty)),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass"))
        )
      }
      "LDAP authentication fails" in {
        assertRuleThrown(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), Secret("pass"), Task.raiseError(TestException("authentication failure")))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mock[LdapAuthorizationService],
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty)),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty))
          ),
          basicHeader = basicAuthHeader("user1:pass"),
          TestException("authentication failure")
        )
      }
      "LDAP authorization fails" in {
        assertRuleThrown(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), Secret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            mockLdapAuthorizationService(User.Id("user1"), Task.raiseError(TestException("authorization failure"))),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty)),
            NonEmptySet.of(Group("g1".nonempty), Group("g2".nonempty))
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
    val rule = new LdapAuthRule(new LdapAuthenticationRule(authenticationSettings), new LdapAuthorizationRule(authorizationSettings))
    val requestContext = MockRequestContext.default.copy(headers = basicHeader.toSet)
    val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    val result = Try(rule.check(requestContext, blockContext).runSyncUnsafe(1 second))
    assertionType match {
      case AssertionType.RuleFulfilled(blockContextAssertion) =>
        inside(result) { case Success(Fulfilled(outBlockContext)) =>
          blockContextAssertion(outBlockContext)
        }
      case AssertionType.RuleRejected =>
        result should be(Success(Rejected))
      case AssertionType.RuleThrownException(ex) =>
        result should be(Failure(ex))
    }
  }

  private def mockLdapAuthenticationService(user: User.Id, secret: Secret, result: Task[Boolean]) = {
    val service = mock[LdapAuthenticationService]
    (service.authenticate _).expects(user, secret).returning(result)
    service
  }

  private def mockLdapAuthorizationService(user: User.Id, result: Task[Set[Group]]) = {
    val service = mock[LdapAuthorizationService]
    (service.groupsOf _).expects(user).returning(result)
    service
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: Group,
                                                 availableGroups: Set[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(LoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }

  private sealed case class TestException(message: String) extends Exception(message)
}