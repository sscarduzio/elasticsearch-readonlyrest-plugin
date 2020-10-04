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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.definitions.{ImpersonatorDef, UserDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.{GroupsRule, Rule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.createMultiResolvableVariableFrom
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.unit.acl.blocks.rules.GroupsRuleTests._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps

class GroupsRuleTests extends WordSpec with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider

  "A GroupsRule" should {
    "match" when {
      "user is not logged but there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(
              User.Id("user1".nonempty),
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              alwaysFulfillingAuthRule(User.Id("user1".nonempty)
              ))
            )
          ),
          loggedUser = None,
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1".nonempty), groupFrom("g1"), UniqueList.of(groupFrom("g1")))
        )
      }
      "user is not logged but there is one auth rule which matches and current group is set" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(
              UserDef(User.Id("user2".nonempty), UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")), alwaysRejectingAuthRule),
              UserDef(User.Id("user1".nonempty), UniqueNonEmptyList.of(groupFrom("g1")), alwaysFulfillingAuthRule(User.Id("user1".nonempty)))
            )
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g1"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1".nonempty), groupFrom("g1"), UniqueList.of(groupFrom("g1")))
        )
      }
      "user is logged and there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(
              UserDef(User.Id("user2".nonempty), UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")), alwaysRejectingAuthRule),
              UserDef(User.Id("user1".nonempty), UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")), alwaysFulfillingAuthRule(User.Id("user1".nonempty)))
            )
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1".nonempty), groupFrom("g1"), UniqueList.of(groupFrom("g1")))
        )
      }
    }
    "not match" when {
      "no group can be resolved" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(createMultiResolvableVariableFrom("group_@{user}".nonempty)(AlwaysRightConvertible.from(Group.apply)).right.get),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1".nonempty), UniqueNonEmptyList.of(groupFrom("group_user1")), alwaysRejectingAuthRule))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "resolved groups don't contain preferred group" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1".nonempty), UniqueNonEmptyList.of(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g2"))
        )
      }
      "there is no user definition for given logged user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1".nonempty), UniqueNonEmptyList.of(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user2".nonempty)),
          preferredGroup = None
        )
      }
      "there is no matching auth rule for given user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1".nonempty), UniqueNonEmptyList.of(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user1".nonempty)),
          preferredGroup = None
        )
      }
      "one auth rule available is throwing an exception" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1".nonempty), UniqueNonEmptyList.of(groupFrom("g1")), alwaysThrowingAuthRule))
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
    val requestContext = MockRequestContext.metadata.copy(
      headers = preferredGroup.map(_.value).map(v => new Header(Header.Name.currentGroup, v)).toSet[Header]
    )
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext,
      loggedUser match {
        case Some(user) => UserMetadata.from(requestContext).withLoggedUser(DirectlyLoggedUser(user))
        case None => UserMetadata.from(requestContext)
      },
      Set.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected())
    }
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
}

object GroupsRuleTests {
  private val alwaysRejectingAuthRule = new AuthenticationRule {
    override protected val impersonators: List[ImpersonatorDef] = Nil
    override val name: Rule.Name = Rule.Name("dummy-rejecting")
    override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = Task.now(Rejected())
    override def exists(user: User.Id): Task[UserExistence] = Task.now(UserExistence.CannotCheck)
  }

  private val alwaysThrowingAuthRule = new AuthenticationRule {
    override protected val impersonators: List[ImpersonatorDef] = Nil
    override def name: Rule.Name = Rule.Name("dummy-throwing")
    override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
      Task.raiseError(new Exception("Sth went wrong"))
    override def exists(user: User.Id): Task[UserExistence] = Task.now(UserExistence.CannotCheck)
  }

  private def alwaysFulfillingAuthRule(user: User.Id) = new AuthenticationRule {
    override protected val impersonators: List[ImpersonatorDef] = Nil
    override def name: Rule.Name = Rule.Name("dummy-fulfilling")
    override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
      Task.now(Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(user)))))
    override def exists(user: User.Id): Task[UserExistence] = Task.now(UserExistence.CannotCheck)
  }
}
