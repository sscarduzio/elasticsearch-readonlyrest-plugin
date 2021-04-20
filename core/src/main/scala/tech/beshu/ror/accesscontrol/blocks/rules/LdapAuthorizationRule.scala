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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult.{Authorized, Unauthorized}
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser}
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class LdapAuthorizationRule(val settings: Settings)
  extends BaseAuthorizationRule {

  override val name: Rule.Name = LdapAuthorizationRule.Name.name

  override protected def authorize[B <: BlockContext](blockContext: B,
                                                      user: LoggedUser): Task[AuthorizationResult] = {
    val requestContext = blockContext.requestContext
    requestContext.currentGroup.toOption match {
      case Some(currentGroup) if !settings.permittedGroups.contains(currentGroup) =>
        Task.now(Unauthorized)
      case Some(_) | None =>
        authorizeWithLdapGroups(requestContext.currentGroup.toOption, user)
    }
  }

  private def authorizeWithLdapGroups(currentGroup: Option[Group],
                                      user: LoggedUser): Task[AuthorizationResult] = {
    settings
      .ldap
      .groupsOf(user.id)
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
                  Authorized(allLdapGroupsIntersection(ldapGroups))
              }
          }
      }
  }

  private def allLdapGroupsIntersection(availableGroups: UniqueNonEmptyList[Group]) = {
    UniqueNonEmptyList.unsafeFromSortedSet(settings.allLdapGroups.intersect(availableGroups)) // it is safe here
  }
}

object LdapAuthorizationRule {

  implicit case object Name extends RuleName[LdapAuthorizationRule] {
    override val name = Rule.Name("ldap_authorization")
  }

  final case class Settings(ldap: LdapAuthorizationService,
                            permittedGroups: UniqueNonEmptyList[Group],
                            allLdapGroups: UniqueNonEmptyList[Group])
}