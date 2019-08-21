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

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult.{Authorized, Unauthorized}
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext

import scala.collection.SortedSet

class LdapAuthorizationRule(val settings: Settings)
  extends BaseAuthorizationRule {

  override val name: Rule.Name = LdapAuthorizationRule.name

  override protected def authorize(requestContext: RequestContext,
                                   blockContext: BlockContext,
                                   user: LoggedUser): Task[AuthorizationResult] = {
    blockContext.currentGroup match {
      case Some(currentGroup) if !settings.permittedGroups.contains(currentGroup) =>
        Task.now(Unauthorized)
      case Some(_) | None =>
        authorizeWithLdapGroups(blockContext, user)
    }
  }

  private def authorizeWithLdapGroups(blockContext: BlockContext, user: LoggedUser): Task[AuthorizationResult] = {
    settings
      .ldap
      .groupsOf(user.id)
      .map(groups => NonEmptySet.fromSet(SortedSet.empty[Group] ++ groups))
      .map {
        case None =>
          Unauthorized
        case Some(ldapGroups) =>
          NonEmptySet.fromSet(SortedSet.empty[Group] ++ ldapGroups.intersect(settings.permittedGroups)) match {
            case None =>
              Unauthorized
            case Some(availableGroups) =>
              blockContext.currentGroup match {
                case Some(currentGroup) if !availableGroups.contains(currentGroup) =>
                  Unauthorized
                case Some(currentGroup) =>
                  Authorized(currentGroup, allLdapGroupsIntersection(ldapGroups))
                case None =>
                  Authorized(pickCurrentGroupFrom(availableGroups), allLdapGroupsIntersection(ldapGroups))
              }
          }
      }
  }

  private def allLdapGroupsIntersection(availableGroups: NonEmptySet[Group]) = {
    NonEmptySet.fromSetUnsafe(settings.allLdapGroups.intersect(availableGroups))
  }
}

object LdapAuthorizationRule {
  val name = Rule.Name("ldap_authorization")

  final case class Settings(ldap: LdapAuthorizationService,
                            permittedGroups: NonEmptySet[Group],
                            allLdapGroups: NonEmptySet[Group])
}