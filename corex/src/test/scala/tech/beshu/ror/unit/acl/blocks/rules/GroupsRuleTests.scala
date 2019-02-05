package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.{GroupsRule, Rule}
import tech.beshu.ror.acl.blocks.{BlockContext, Const, RequestContextInitiatedBlockContext, Variable}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.blocks.rules.GroupsRuleTests._

import scala.concurrent.duration._
import scala.language.postfixOps

class GroupsRuleTests extends WordSpec with Inside with BlockContextAssertion[GroupsRule.Settings] {

  "A GroupsRule" should {
    "match" when {
      "user is not logged but there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Const(groupFrom("g1"))),
            usersDefinitions = NonEmptySet.one(
              UserDef(User.Id("user1"), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysFulfillingAuthRule(User.Id("user1")))
            )
          ),
          loggedUser = None,
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1"), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
        )
      }
      "user is not logged but there is one auth rule which matches and current group is set" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Const(groupFrom("g1"))),
            usersDefinitions = NonEmptySet.of(
              UserDef(User.Id("user2"), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysRejectingAuthRule),
              UserDef(User.Id("user1"), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysFulfillingAuthRule(User.Id("user1")))
            )
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g1"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1"), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
        )
      }
      "user is logged and there is one auth rule which matches" in {
        assertMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Const(groupFrom("g1"))),
            usersDefinitions = NonEmptySet.of(
              UserDef(User.Id("user2"), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysRejectingAuthRule),
              UserDef(User.Id("user1"), NonEmptySet.of(groupFrom("g1"), groupFrom("g2")), alwaysFulfillingAuthRule(User.Id("user1")))
            )
          ),
          loggedUser = Some(User.Id("user1")),
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user1"), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
        )
      }
    }
    "not match" when {
      "no group can be resolved" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Variable(ValueWithVariable("group_@{user}"), rv => Right(groupFrom(rv.value)))),
            usersDefinitions = NonEmptySet.one(UserDef(User.Id("user1"), NonEmptySet.one(groupFrom("group_user1")), alwaysRejectingAuthRule))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "resolved groups don't contain preferred group" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Const(groupFrom("g1"))),
            usersDefinitions = NonEmptySet.one(UserDef(User.Id("user1"), NonEmptySet.one(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = None,
          preferredGroup = Some(groupFrom("g2"))
        )
      }
      "there is no user definition for given logged user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Const(groupFrom("g1"))),
            usersDefinitions = NonEmptySet.one(UserDef(User.Id("user1"), NonEmptySet.one(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "there is no matching auth rule for given user" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Const(groupFrom("g1"))),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1"), NonEmptySet.one(groupFrom("g1")), alwaysRejectingAuthRule))
          ),
          loggedUser = Some(User.Id("user1")),
          preferredGroup = None
        )
      }
      "one auth rule available is throwing an exception" in {
        assertNotMatchRule(
          settings = GroupsRule.Settings(
            groups = NonEmptySet.one(Const(groupFrom("g1"))),
            usersDefinitions = NonEmptySet.of(UserDef(User.Id("user1"), NonEmptySet.one(groupFrom("g1")), alwaysThrowingAuthRule))
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
    val rule = new GroupsRule(settings)
    val requestContext = MockRequestContext(
      headers = preferredGroup.map(_.value).map(v => Header(Header.Name.currentGroup, v)).toSet[Header]
    )
    val blockContext = loggedUser
      .map(LoggedUser(_))
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
        loggedUser = Some(LoggedUser(user)),
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
      Task.now(Fulfilled(blockContext.withLoggedUser(LoggedUser(user))))
  }
}
