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
import cats.data.NonEmptySet
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.definitions.{ImpersonatorDef, UserDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, NoImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.rules.{GroupsRule, Rule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.createMultiResolvableVariableFrom
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.unit.acl.blocks.rules.GroupsRuleTests._
import tech.beshu.ror.utils.TestsUtils
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps
import cats.Eq
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.UserIdPatterns
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern

import scala.collection.immutable.SortedSet

class GroupsRuleTests extends AnyWordSpec with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider

  "A GroupsRule" should {
    "match" when {
      "user is not logged but there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(
              userIdPatterns("user1"),
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              alwaysFulfillingAuthRule(User.Id("user1")
              ))
            )
          ),
          loggedUser = None,
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1"), groupFrom("g1"), UniqueList.of(groupFrom("g1")))
        )
      }
      "user is not logged but there is one auth rule which matches and current group is set" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(
              UserDef(
                userIdPatterns("user2"),
                UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
                alwaysRejectingAuthRule
              ),
              UserDef(
                userIdPatterns("user1"),
                UniqueNonEmptyList.of(groupFrom("g1")),
                alwaysFulfillingAuthRule(User.Id("user1"))
              )
            )
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g1"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1"), groupFrom("g1"), UniqueList.of(groupFrom("g1")))
        )
      }
      "user is logged and there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(
              UserDef(
                userIdPatterns("user2"),
                UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
                alwaysRejectingAuthRule
              ),
              UserDef(
                userIdPatterns("user1"),
                UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
                alwaysFulfillingAuthRule(User.Id("user1"))
              )
            )
          ),
          loggedUser = Some(User.Id("user1")),
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1"), groupFrom("g1"), UniqueList.of(groupFrom("g1")))
        )
      }
    }
    "not match" when {
      "no group can be resolved" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(Group.apply)).right.get),
            usersDefinitions = NonEmptySet.of(UserDef(
              userIdPatterns("user1"),
              UniqueNonEmptyList.of(groupFrom("group_user1")),
              alwaysRejectingAuthRule
            ))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "resolved groups don't contain preferred group" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(userIdPatterns("user1"), UniqueNonEmptyList.of(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g2"))
        )
      }
      "there is no user definition for given logged user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(userIdPatterns("user1"), UniqueNonEmptyList.of(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "there is no matching auth rule for given user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(userIdPatterns("user1"), UniqueNonEmptyList.of(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user1")),
          preferredGroup = None
        )
      }
      "one auth rule available is throwing an exception" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
            usersDefinitions = NonEmptySet.of(UserDef(userIdPatterns("user1"), UniqueNonEmptyList.of(groupFrom("g1")), alwaysThrowingAuthRule))
          ),
          loggedUser = Some(User.Id("user1")),
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
    val rule = new GroupsRule(settings, TestsUtils.userIdEq)
    val requestContext = MockRequestContext.metadata.copy(
      headers = preferredGroup.map(_.value).map(v => new Header(Header.Name.currentGroup, v)).toSet[Header]
    )
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext,
      loggedUser match {
        case Some(user) => UserMetadata.from(requestContext).withLoggedUser(DirectlyLoggedUser(user))
        case None => UserMetadata.from(requestContext)
      },
      Set.empty,
      List.empty
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

  private def userIdPatterns(ids: String*) = {
    UserIdPatterns(
      NonEmptySet
        .fromSetUnsafe(SortedSet.empty[String] ++ ids.toSet)
        .map(str => UserIdPattern(NonEmptyString.unsafeFrom(str)))
    )
  }
}

object GroupsRuleTests {
  private val alwaysRejectingAuthRule = new AuthenticationRule {
    override protected val impersonators: List[ImpersonatorDef] = Nil
    override val name: Rule.Name = Rule.Name("dummy-rejecting")
    override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = Task.now(Rejected())
    override def exists(user: User.Id)
                       (implicit userIdEq: Eq[User.Id]): Task[UserExistence] =
      Task.now(UserExistence.CannotCheck)

    override protected val caseMappingEquality: UserIdCaseMappingEquality = TestsUtils.userIdEq
  }

  private val alwaysThrowingAuthRule = new AuthenticationRule {
    override protected val impersonators: List[ImpersonatorDef] = Nil
    override def name: Rule.Name = Rule.Name("dummy-throwing")
    override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
      Task.raiseError(new Exception("Sth went wrong"))
    override def exists(user: User.Id)
                       (implicit userIdEq: Eq[User.Id]): Task[UserExistence] = Task.now(UserExistence.CannotCheck)
    override protected val caseMappingEquality: UserIdCaseMappingEquality = TestsUtils.userIdEq
  }

  private def alwaysFulfillingAuthRule(user: User.Id) = new AuthenticationRule with NoImpersonationSupport {
    override protected val impersonators: List[ImpersonatorDef] = Nil
    override def name: Rule.Name = Rule.Name("dummy-fulfilling")
    override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
      Task.now(Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(user)))))
    override protected val caseMappingEquality: UserIdCaseMappingEquality = TestsUtils.userIdEq
  }
}
