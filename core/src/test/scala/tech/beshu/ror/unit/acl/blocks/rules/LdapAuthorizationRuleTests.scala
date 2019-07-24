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
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapAuthorizationService
import tech.beshu.ror.acl.blocks.rules.LdapAuthorizationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, RequestContextInitiatedBlockContext}
import tech.beshu.ror.acl.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.{BlockContextAssertion, _}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class LdapAuthorizationRuleTests
  extends WordSpec
    with Inside
    with MockFactory
    with BlockContextAssertion {

  "An LdapAuthorizationRule" should {
    "match" when {
      "user has at least one LDAP group which is permitted" in {
        assertMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mockLdapService(User.Id("user1".nonempty), Task.now(Set(groupFrom("g1"), groupFrom("g2")))),
            permittedGroups = NonEmptySet.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = NonEmptySet.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            user = User.Id("user1".nonempty),
            group = groupFrom("g1"),
            availableGroups = Set(groupFrom("g1"), groupFrom("g2"))
          )
        )
      }
    }
    "not match" when {
      "user is not logged" in {
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationService],
            permittedGroups = NonEmptySet.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = NonEmptySet.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "user has no group which is permitted" in {
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mockLdapService(User.Id("user1".nonempty), Task.now(Set(groupFrom("g5")))),
            permittedGroups = NonEmptySet.of(groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = NonEmptySet.of(groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None
        )
      }
      "user current group is not permitted" in {
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationService],
            permittedGroups = NonEmptySet.of(groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = NonEmptySet.of(groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = Some(groupFrom("g3"))
        )
      }
      "LDAP service fails" in {
        assertRuleThrown(
          settings = LdapAuthorizationRule.Settings(
            ldap = mockLdapService(User.Id("user1".nonempty), Task.raiseError(TestException("LDAP failed"))),
            permittedGroups = NonEmptySet.of(groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = NonEmptySet.of(groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None,
          exception = TestException("LDAP failed")
        )
      }
    }
  }

  private def assertMatchRule(settings: LdapAuthorizationRule.Settings, loggedUser: Option[User.Id], preferredGroup: Option[Group])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, loggedUser, preferredGroup, AssertionType.RuleFulfilled(blockContextAssertion))

  private def assertNotMatchRule(settings: LdapAuthorizationRule.Settings, loggedUser: Option[User.Id], preferredGroup: Option[Group]): Unit =
    assertRule(settings, loggedUser, preferredGroup, AssertionType.RuleRejected)

  private def assertRuleThrown(settings: LdapAuthorizationRule.Settings, loggedUser: Option[User.Id], preferredGroup: Option[Group], exception: Throwable): Unit =
    assertRule(settings, loggedUser, preferredGroup, AssertionType.RuleThrownException(exception))

  private def assertRule(settings: LdapAuthorizationRule.Settings,
                         loggedUser: Option[User.Id],
                         preferredGroup: Option[Group],
                         assertionType: AssertionType): Unit = {
    val rule = new LdapAuthorizationRule(settings)
    val requestContext = MockRequestContext(
      headers = preferredGroup.map(_.value).map(v => Header(Header.Name.currentGroup, v)).toSet[Header]
    )
    val blockContext = loggedUser
      .map(DirectlyLoggedUser(_))
      .foldLeft(RequestContextInitiatedBlockContext.fromRequestContext(requestContext): BlockContext)(_ withLoggedUser _)
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

  private def mockLdapService(user: User.Id, result: Task[Set[Group]]) = {
    val service = mock[LdapAuthorizationService]
    (service.groupsOf _).expects(user).returning(result)
    service
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: Group,
                                                 availableGroups: Set[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }

  private sealed case class TestException(message: String) extends Exception(message)
}
