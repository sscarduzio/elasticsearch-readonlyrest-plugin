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
package tech.beshu.ror.accesscontrol.blocks.rules.auth

import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, Group, GroupIdLike, GroupsLogic, LoggedUser, PermittedGroupIds, User}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class ExternalAuthorizationRule(val settings: ExternalAuthorizationRule.Settings,
                                override implicit val userIdCaseSensitivity: CaseSensitivity,
                                override val impersonation: Impersonation)
  extends BaseAuthorizationRule {

  private val userMatcher = PatternsMatcher.create[User.Id](settings.users.toSet)

  override val name: Rule.Name = ExternalAuthorizationRule.Name.name

  override protected val groupsPermittedByRule: PermittedGroupIds = settings.permittedGroupsLogic.permittedGroupIds

  override protected def loggedUserPreconditionCheck(user: LoggedUser): Either[Unit, Unit] = {
    Either.cond(userMatcher.`match`(user.id), (), ())
  }

  override protected def userGroups[B <: BlockContext](blockContext: B,
                                                       user: LoggedUser,
                                                       permittedGroupIds: Set[GroupIdLike]): Task[UniqueList[Group]] =
    settings.service.grantsFor(user.id) // todo: filtering?

  override protected def mockedGroupsOf(user: User.Id, mocksProvider: MocksProvider)
                                       (implicit requestId: RequestId): Groups = {
    mocksProvider
      .externalAuthorizationServiceWith(settings.service.id)
      .map { mock =>
        mock
          .users
          .find(_.id === user)
          .map(m => Groups.Present(UniqueList.of(m.groups.toSeq: _*)))
          .getOrElse(Groups.Present(UniqueList.empty))
      }
      .getOrElse {
        Groups.CannotCheck
      }
  }

  override protected def calculateAllowedGroupsForUser(usersGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]] =
    settings.permittedGroupsLogic.availableGroupsFrom(usersGroups)
}

object ExternalAuthorizationRule {

  implicit case object Name extends RuleName[ExternalAuthorizationRule] {
    override val name: Rule.Name = Rule.Name("groups_provider_authorization")
  }

  final case class Settings(service: ExternalAuthorizationService,
                            permittedGroupsLogic: GroupsLogic,
                            users: UniqueNonEmptyList[User.Id])

}