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

import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.LdapAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.LdapAuthorizationRule.Settings.LdapGroupsLogic
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthorizationRule.GroupsPotentiallyPermittedByRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.{NegativeGroupsLogic, PositiveGroupsLogic}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class LdapAuthorizationRule(val settings: Settings,
                            override implicit val userIdCaseSensitivity: CaseSensitivity,
                            override val impersonation: Impersonation)
  extends BaseAuthorizationRule {

  override val name: Rule.Name = LdapAuthorizationRule.Name.name

  override protected val groupsPotentiallyPermittedByRule: GroupsPotentiallyPermittedByRule =
    settings match
      case Settings.ForLdapWithoutGroupsFiltering(_, _) =>
        GroupsPotentiallyPermittedByRule.All
      case Settings.ForLdapWithGroupsFiltering(_, groupsLogic) =>
        groupsLogic match
          case LdapGroupsLogic.Negative(_, potentiallyPermittedGroups) =>
            GroupsPotentiallyPermittedByRule.Selected(potentiallyPermittedGroups)
          case LdapGroupsLogic.Positive(groupsLogic) =>
            GroupsPotentiallyPermittedByRule.Selected(groupsLogic.groupIds)

  override protected def userGroups[B <: BlockContext](blockContext: B,
                                                       user: LoggedUser,
                                                       groupsPotentiallyPermittedByRule: GroupsPotentiallyPermittedByRule)
                                                      (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    settings match
      case Settings.ForLdapWithoutGroupsFiltering(ldap, _) =>
        ldap.groupsOf(user.id)
      case Settings.ForLdapWithGroupsFiltering(ldap, groupsLogic) =>
        groupsLogic match
          case LdapGroupsLogic.Negative(_, potentiallyPermittedGroups) =>
            ldap.groupsOf(user.id, potentiallyPermittedGroups.groupIds.toSet)
          case LdapGroupsLogic.Positive(groupsLogic) =>
            ldap.groupsOf(user.id, groupsLogic.groupIds.groupIds.toSet)
  }

  override protected def calculateAllowedGroupsForUser(usersGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]] =
    settings match
      case Settings.ForLdapWithoutGroupsFiltering(_, groupsLogic) =>
        groupsLogic.availableGroupsFrom(usersGroups)
      case Settings.ForLdapWithGroupsFiltering(_, groupsLogic) =>
        groupsLogic.groupsLogic.availableGroupsFrom(usersGroups)

  override protected def mockedGroupsOf(user: User.Id,
                                        mocksProvider: MocksProvider)
                                       (implicit requestId: RequestId): Groups = {
    mocksProvider
      .ldapServiceWith(settings.ldap.id)
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
}

object LdapAuthorizationRule {

  implicit case object Name extends RuleName[LdapAuthorizationRule] {
    override val name = Rule.Name("ldap_authorization")
  }

  sealed trait Settings {
    def ldap: LdapAuthorizationService
  }

  object Settings:

    final case class ForPositiveGroupsLogic(ldap: LdapAuthorizationService,
                                            groupsLogic: PositiveGroupsLogic) extends Settings


    final case class ForLdapWithoutGroupsFiltering(ldap: LdapAuthorizationService.WithoutGroupsFiltering,
                                                   groupsLogic: GroupsLogic) extends Settings

    final case class ForLdapWithGroupsFiltering(ldap: LdapAuthorizationService.WithGroupsFiltering,
                                                groupsLogic: LdapGroupsLogic) extends Settings


    def apply(ldap: LdapAuthorizationService, groupsLogic: PositiveGroupsLogic): Settings = ldap match
      case ldap: LdapAuthorizationService.WithoutGroupsFiltering =>
        ForLdapWithoutGroupsFiltering(ldap, groupsLogic)
      case ldap: LdapAuthorizationService.WithGroupsFiltering =>
        ForLdapWithGroupsFiltering(ldap, LdapGroupsLogic.Positive(groupsLogic))

    sealed trait LdapGroupsLogic {
      def groupsLogic: GroupsLogic
    }

    object LdapGroupsLogic:
      final case class Positive(groupsLogic: PositiveGroupsLogic) extends LdapGroupsLogic

      final case class Negative(groupsLogic: NegativeGroupsLogic, potentiallyPermittedGroups: GroupIds) extends LdapGroupsLogic

}