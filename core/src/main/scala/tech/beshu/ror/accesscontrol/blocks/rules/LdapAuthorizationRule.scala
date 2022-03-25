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
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.base.{BaseAuthorizationRule, Rule}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, User}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class LdapAuthorizationRule(val settings: Settings,
                            override val impersonation: Impersonation,
                            override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseAuthorizationRule {

  override val name: Rule.Name = LdapAuthorizationRule.Name.name

  override protected val groupsPermittedByRule: UniqueNonEmptyList[Group] =
    settings.permittedGroups.getPermittedGroups()

  override protected val groupsPermittedByAllRulesOfThisType: UniqueNonEmptyList[Group] =
    settings.allLdapGroups

  override protected def userGroups[B <: BlockContext](blockContext: B,
                                                       user: LoggedUser): Task[UniqueList[Group]] =
    settings.ldap.groupsOf(user.id)

  override protected def mockedGroupsOf(user: User.Id,
                                        mocksProvider: MocksProvider)
                                       (implicit requestId: RequestId,
                                        eq: Eq[User.Id]): Groups = {
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

  final case class Settings(ldap: LdapAuthorizationService,
                            permittedGroups: GroupsLogic,
                            allLdapGroups: UniqueNonEmptyList[Group])

  sealed trait GroupsLogic {
    def getPermittedGroups(): UniqueNonEmptyList[Group]

    def availableGroupsFrom(userGroups: Set[Group], ruleGroups: Set[Group]): Option[UniqueNonEmptyList[Group]]
  }

  object GroupsLogic {

    final case class Or(permittedGroups: UniqueNonEmptyList[Group]) extends GroupsLogic {
      override def getPermittedGroups = permittedGroups

      override def availableGroupsFrom(userGroups: Set[Group], ruleGroups: Set[Group]): Option[UniqueNonEmptyList[Group]] = {
        UniqueNonEmptyList.fromSet(userGroups intersect ruleGroups)
      }
    }

    final case class And(permittedGroups: UniqueNonEmptyList[Group]) extends GroupsLogic {
      override def getPermittedGroups = permittedGroups

      override def availableGroupsFrom(userGroups: Set[Group], ruleGroups: Set[Group]): Option[UniqueNonEmptyList[Group]] = {
        val intersection = userGroups intersect ruleGroups
        UniqueNonEmptyList.fromSet(
          if (intersection === ruleGroups) ruleGroups else Set.empty
        )
      }
    }
  }
}