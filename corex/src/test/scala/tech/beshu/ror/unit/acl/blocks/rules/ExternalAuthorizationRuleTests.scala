package tech.beshu.ror.unit.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.aDomain.{Group, Header, LoggedUser, User}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.acl.blocks.rules.ExternalAuthorizationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, RequestContextInitiatedBlockContext}
import tech.beshu.ror.mocks.MockRequestContext

import scala.concurrent.duration._
import scala.language.postfixOps

class ExternalAuthorizationRuleTests
  extends WordSpec with MockFactory with Inside with BlockContextAssertion[ExternalAuthorizationRule.Settings]{

  "An ExternalAuthorizationRule" should {
    "match" when {
      "user is logged and match configured used list" when {
        "has current groups and the groups is present in intersection set" in {
          val service = mock[ExternalAuthorizationService]
          (service.grantsFor _).expects(LoggedUser(User.Id("user2"))).returning(Task.now(Set(groupFrom("g2"), groupFrom("g3"))))

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service,
              NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
              NonEmptySet.of(User.Id("user1"), User.Id("user2"))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = Some(groupFrom("g2"))
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user2"), groupFrom("g2"), Set(groupFrom("g2")))
          )
        }
        "doesn't have current group set, but there is non empty intersection set between fetched groups and configured ones" in {
          val service = mock[ExternalAuthorizationService]
          (service.grantsFor _).expects(LoggedUser(User.Id("user2"))).returning(Task.now(Set(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"))))

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service,
              NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
              NonEmptySet.of(User.Id("user1"), User.Id("user2"))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user2"), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
          )
        }
        "configured user name has wildcard" in {
          val service = mock[ExternalAuthorizationService]
          (service.grantsFor _).expects(LoggedUser(User.Id("user2"))).returning(Task.now(Set(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"))))

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service,
              NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
              NonEmptySet.of(User.Id("*"))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("user2"), groupFrom("g1"), Set(groupFrom("g1"), groupFrom("g2")))
          )
        }
      }
    }
    "not match" when {
      "user is not logged in" in {
        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            mock[ExternalAuthorizationService],
            NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
            NonEmptySet.of(User.Id("user1"))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "user is logged, but his id is not listed on user config list" in {
        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            mock[ExternalAuthorizationService],
            NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
            NonEmptySet.of(User.Id("user1"))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "authorization service returns empty groups list" in {
        val service = mock[ExternalAuthorizationService]
        (service.grantsFor _).expects(LoggedUser(User.Id("user2"))).returning(Task.now(Set.empty))

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service,
            NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
            NonEmptySet.of(User.Id("*"))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "authorization service groups for given user has empty intersection with configured groups" in {
        val service = mock[ExternalAuthorizationService]
        (service.grantsFor _).expects(LoggedUser(User.Id("user2"))).returning(Task.now(Set(groupFrom("g3"), groupFrom("g4"))))

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service,
            NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
            NonEmptySet.of(User.Id("*"))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "current group is set for a given user but it's not present in intersection groups set" in {
        val service = mock[ExternalAuthorizationService]
        (service.grantsFor _).expects(LoggedUser(User.Id("user2"))).returning(Task.now(Set(groupFrom("g1"), groupFrom("g2"))))

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service,
            NonEmptySet.of(groupFrom("g1"), groupFrom("g2")),
            NonEmptySet.of(User.Id("*"))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = Some(groupFrom("g3"))
        )
      }
    }
  }

  private def assertMatchRule(settings: ExternalAuthorizationRule.Settings, loggedUser: Option[User.Id], preferredGroup: Option[Group])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, loggedUser, preferredGroup, Some(blockContextAssertion))

  private def assertNotMatchRule(settings: ExternalAuthorizationRule.Settings, loggedUser: Option[User.Id], preferredGroup: Option[Group]): Unit =
    assertRule(settings, loggedUser, preferredGroup, blockContextAssertion = None)

  private def assertRule(settings: ExternalAuthorizationRule.Settings,
                         loggedUser: Option[User.Id],
                         preferredGroup: Option[Group],
                         blockContextAssertion: Option[BlockContext => Unit]): Unit = {
    val rule = new ExternalAuthorizationRule(settings)
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
