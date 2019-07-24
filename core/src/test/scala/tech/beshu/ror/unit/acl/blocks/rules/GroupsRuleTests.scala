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
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.{GroupsRule, Rule}
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariableCreator.createMultiResolvableVariableFrom
import tech.beshu.ror.acl.blocks.{BlockContext, RequestContextInitiatedBlockContext}
import tech.beshu.ror.acl.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.unit.acl.blocks.rules.GroupsRuleTests._
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

class GroupsRuleTests extends WordSpec with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider

  "A GroupsRule" should {
    "match" when {
      "user is not logged but there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.one(
              UserDef(User.Id("user1".nonempty), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysFulfillingAuthRule(User.Id("user1".nonempty)))
            )
          ),
          loggedUser = None,
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1".nonempty), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
        )
      }
      "user is not logged but there is one auth rule which matches and current group is set" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(
              UserDef(User.Id("user2".nonempty), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysRejectingAuthRule),
              UserDef(User.Id("user1".nonempty), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysFulfillingAuthRule(User.Id("user1".nonempty)))
            )
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g1"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1".nonempty), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
        )
      }
      "user is logged and there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(
              UserDef(User.Id("user2".nonempty), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysRejectingAuthRule),
              UserDef(User.Id("user1".nonempty), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysFulfillingAuthRule(User.Id("user1".nonempty)))
            )
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1".nonempty), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
        )
      }
    }
    "not match" when {
      "no group can be resolved" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(createMultiResolvableVariableFrom("group_@{user}".nonempty)(AlwaysRightConvertible.from(Group.apply)).right.get),
            usersDefinitions = NonEmptySet.one(UserDef(User.Id("user1".nonempty), NonEmptySet.one(groupFrom("group_user1")), alwaysRejectingAuthRule))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "resolved groups don't contain preferred group" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.one(UserDef(User.Id("user1".nonempty), NonEmptySet.one(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g2"))
        )
      }
      "there is no user definition for given logged user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.one(UserDef(User.Id("user1".nonempty), NonEmptySet.one(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user2".nonempty)),
          preferredGroup = None
        )
      }
      "there is no matching auth rule for given user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1".nonempty), NonEmptySet.one(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None
        )
      }
      "one auth rule available is throwing an exception" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1".nonempty), NonEmptySet.one(groupFrom("g1")), alwaysThrowingAuthRule))
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None
        )
      }
    }
  }

  private def assertMatchRule(settings: GroupsRule.Settings, loggedUser: Option[User.Id], preferredGroup: Option[Group])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, loggedUser, preferredGroup, Some(blockContextAssertion))

  private def assertNotMatchRule(settings: GroupsRule.Settings, loggedUser: Option[User.Id], preferredGroup: Option[Group]): Unit =
    assertRule(settings, loggedUser, preferredGroup, blockContextAssertion = None)

  private def assertRule(settings: GroupsRule.Settings,
                         loggedUser: Option[User.Id],
                         preferredGroup: Option[Group],
                         blockContextAssertion: Option[BlockContext => Unit]): Unit = {
    val rule = new GroupsRule(settings)
    val requestContext = MockRequestContext(
      headers = preferredGroup.map(_.value).map(v => Header(Header.Name.currentGroup, v)).toSet[Header]
    )
    val blockContext = loggedUser
      .map(DirectlyLoggedUser(_))
      .foldLeft(RequestContextInitiatedBlockContext.fromRequestContext(requestContext): BlockContext)(_ withLoggedUser _)
    val result = rule.check(requestContext, blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected)
    }
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: Group, availableGroups: Set[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }
}

object GroupsRuleTests {
  private val alwaysRejectingAuthRule: AuthenticationRule = new AuthenticationRule {
    override def name: Rule.Name = Rule.Name("dummy-rejecting")
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] = Task.now(Rejected)
  }

  private val alwaysThrowingAuthRule: AuthenticationRule = new AuthenticationRule {
    override def name: Rule.Name = Rule.Name("dummy-throwing")
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
      Task.raiseError(new Exception("Sth went wrong"))
  }

  private def alwaysFulfillingAuthRule(user: User.Id): AuthenticationRule = new AuthenticationRule {
    override def name: Rule.Name = Rule.Name("dummy-fulfilling")
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
      Task.now(Fulfilled(blockContext.withLoggedUser(DirectlyLoggedUser(user))))
  }
}
