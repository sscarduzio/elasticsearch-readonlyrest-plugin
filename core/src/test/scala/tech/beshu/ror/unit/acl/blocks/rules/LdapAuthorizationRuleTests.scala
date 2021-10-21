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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.{BlockContextAssertion, _}
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class LdapAuthorizationRuleTests
  extends AnyWordSpec
    with Inside
    with MockFactory
    with BlockContextAssertion {

  "An LdapAuthorizationRule" should {
    "match" when {
      "user has at least one LDAP group which is permitted" in {
        assertMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mockLdapService(User.Id("user1"), Task.now(UniqueList.of(groupFrom("g1"), groupFrom("g2")))),
            permittedGroups = UniqueNonEmptyList.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = UniqueNonEmptyList.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1")),
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            user = User.Id("user1"),
            group = groupFrom("g2"),
            availableGroups = UniqueList.of(groupFrom("g2"), groupFrom("g1"))
          )
        )
      }
    }
    "not match" when {
      "user is not logged" in {
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationService],
            permittedGroups = UniqueNonEmptyList.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = UniqueNonEmptyList.of(groupFrom("g3"), groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "user has no group which is permitted" in {
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mockLdapService(User.Id("user1"), Task.now(UniqueList.of(groupFrom("g5")))),
            permittedGroups = UniqueNonEmptyList.of(groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = UniqueNonEmptyList.of(groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1")),
          preferredGroup = None
        )
      }
      "user current group is not permitted" in {
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationService],
            permittedGroups = UniqueNonEmptyList.of(groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = UniqueNonEmptyList.of(groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1")),
          preferredGroup = Some(groupFrom("g3"))
        )
      }
      "LDAP service fails" in {
        assertRuleThrown(
          settings = LdapAuthorizationRule.Settings(
            ldap = mockLdapService(User.Id("user1"), Task.raiseError(TestException("LDAP failed"))),
            permittedGroups = UniqueNonEmptyList.of(groupFrom("g2"), groupFrom("g1")),
            allLdapGroups = UniqueNonEmptyList.of(groupFrom("g2"), groupFrom("g1"))
          ),
          loggedUser = Some(User.Id("user1")),
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
    val rule = new LdapAuthorizationRule(settings, Impersonation.Disabled, UserIdEq.caseSensitive)
    val requestContext = MockRequestContext.metadata.copy(
      headers = preferredGroup.map(_.value).map(v => new Header(Header.Name.currentGroup, v)).toSet[Header]
    )
    val blockContext = loggedUser
      .map(DirectlyLoggedUser.apply)
      .foldLeft(CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)) {
        case (bc, user) => bc.withUserMetadata(_.withLoggedUser(user))
      }
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

  private def mockLdapService(user: User.Id, result: Task[UniqueList[Group]]) = {
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
