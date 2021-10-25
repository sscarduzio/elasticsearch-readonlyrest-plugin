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
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.ExternalAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, Header, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps

class ExternalAuthorizationRuleTests
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion {

  private implicit val defaultCaseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

  "An ExternalAuthorizationRule" should {
    "match" when {
      "user is logged and match configured used list" when {
        "has current groups and the groups is present in intersection set" in {
          val service = mock[ExternalAuthorizationService]
          (service.grantsFor _)
            .expects(DirectlyLoggedUser(User.Id("user2")))
            .returning(Task.now(UniqueList.of(groupFrom("g2"), groupFrom("g3"))))

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service,
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = Some(groupFrom("g2"))
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              User.Id("user2"),
              groupFrom("g2"),
              UniqueList.of(groupFrom("g2"))
            )
          )
        }
        "doesn't have current group set, but there is non empty intersection set between fetched groups and configured ones" in {
          val service = mock[ExternalAuthorizationService]
          (service.grantsFor _)
            .expects(DirectlyLoggedUser(User.Id("user2")))
            .returning(Task.now(UniqueList.of(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"))))

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service,
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              User.Id("user2"),
              groupFrom("g1"),
              UniqueList.of(groupFrom("g1"), groupFrom("g2"))
            )
          )
        }
        "configured user name has wildcard" in {
          val service = mock[ExternalAuthorizationService]
          (service.grantsFor _)
            .expects(DirectlyLoggedUser(User.Id("user2")))
            .returning(Task.now(UniqueList.of(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"))))

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service,
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
              UniqueNonEmptyList.of(User.Id("*"))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              User.Id("user2"),
              groupFrom("g1"),
              UniqueList.of(groupFrom("g1"), groupFrom("g2"))
            )
          )
        }
      }
    }
    "not match" when {
      "user is not logged in" in {
        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            mock[ExternalAuthorizationService],
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(User.Id("user1"))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "user is logged, but his id is not listed on user config list" in {
        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            mock[ExternalAuthorizationService],
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(User.Id("user1"))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "authorization service returns empty groups list" in {
        val service = mock[ExternalAuthorizationService]
        (service.grantsFor _)
          .expects(DirectlyLoggedUser(User.Id("user2")))
          .returning(Task.now(UniqueList.empty))

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service,
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(User.Id("*"))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "authorization service groups for given user has empty intersection with configured groups" in {
        val service = mock[ExternalAuthorizationService]
        (service.grantsFor _)
          .expects(DirectlyLoggedUser(User.Id("user2")))
          .returning(Task.now(UniqueList.of(groupFrom("g3"), groupFrom("g4"))))

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service,
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            UniqueNonEmptyList.of(User.Id("*"))
          ),
          loggedUser = Some(User.Id("user2")),
          preferredGroup = None
        )
      }
      "current group is set for a given user but it's not present in intersection groups set" in {
        val service = mock[ExternalAuthorizationService]

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service,
            permittedGroups = UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            allExternalServiceGroups = UniqueNonEmptyList.of(groupFrom("g1"), groupFrom("g2")),
            users = UniqueNonEmptyList.of(User.Id("*"))
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
    val rule = new ExternalAuthorizationRule(settings, Impersonation.Disabled, UserIdEq.caseSensitive)
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
}
