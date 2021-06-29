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

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult.{Authorized, Unauthorized}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, User}
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ExternalAuthorizationRule(val settings: ExternalAuthorizationRule.Settings,
                                implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseAuthorizationRule {

  private val userMatcher = MatcherWithWildcardsScalaAdapter[User.Id](settings.users.toSet)

  override val name: Rule.Name = ExternalAuthorizationRule.Name.name

  override protected def authorize[B <: BlockContext](blockContext: B,
                                                      user: LoggedUser): Task[AuthorizationResult] = {
    if (userMatcher.`match`(user.id)) checkUserGroups(user, blockContext.userMetadata.currentGroup)
    else Task.now(Unauthorized)
  }

  private def checkUserGroups(user: LoggedUser, currentGroup: Option[Group]): Task[AuthorizationResult] = {
    settings
      .service
      .grantsFor(user)
      .map(groups => UniqueNonEmptyList.fromSortedSet(groups))
      .map {
        case None =>
          Unauthorized
        case Some(ldapGroups) =>
          UniqueNonEmptyList.fromSortedSet(settings.permittedGroups.intersect(ldapGroups)) match {
            case None =>
              Unauthorized
            case Some(availableGroups) =>
              currentGroup match {
                case Some(group) if !availableGroups.contains(group) =>
                  Unauthorized
                case Some(_) | None =>
                  Authorized(allExternalServiceGroupsIntersection(ldapGroups))
              }
          }
      }
  }

  private def allExternalServiceGroupsIntersection(availableGroups: UniqueNonEmptyList[Group]) = {
    UniqueNonEmptyList.unsafeFromSortedSet(settings.allExternalServiceGroups.intersect(availableGroups)) // it is safe here
  }
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