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
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.Eq
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, User}
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class ExternalAuthorizationRule(val settings: ExternalAuthorizationRule.Settings,
                                override val mocksProvider: MocksProvider,
                                implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseAuthorizationRule {

  private val userMatcher = MatcherWithWildcardsScalaAdapter[User.Id](settings.users.toSet)

  override val name: Rule.Name = ExternalAuthorizationRule.Name.name

  override protected val groupsPermittedByRule: UniqueNonEmptyList[Group] =
    settings.permittedGroups

  override protected val groupsPermittedByAllRulesOfThisType: UniqueNonEmptyList[Group] =
    settings.allExternalServiceGroups

  override protected def loggedUserPreconditionCheck(user: LoggedUser): Either[Unit, Unit] = {
    Either.cond(userMatcher.`match`(user.id), (), ())
  }

  override protected def userGroups[B <: BlockContext](blockContext: B,
                                                       user: LoggedUser): Task[UniqueList[Group]] =
    settings.service.grantsFor(user)

  override protected[rules] def mockedGroupsOf(user: User.Id)
                                              (implicit requestId: RequestId,
                                               userIdEq: Eq[User.Id]): Groups = ???

}

object ExternalAuthorizationRule {

  implicit case object Name extends RuleName[ExternalAuthorizationRule] {
    override val name = Rule.Name("groups_provider_authorization")
  }

  final case class Settings(service: ExternalAuthorizationService,
                            permittedGroups: UniqueNonEmptyList[Group],
                            allExternalServiceGroups: UniqueNonEmptyList[Group],
                            users: UniqueNonEmptyList[User.Id])

}