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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, User}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class LdapAuthorizationRule(val settings: Settings,
                            override val mocksProvider: MocksProvider,
                            override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseAuthorizationRule {

  override val name: Rule.Name = LdapAuthorizationRule.Name.name

  override protected val groupsPermittedByRule: UniqueNonEmptyList[Group] =
    settings.permittedGroups

  override protected val groupsPermittedByAllRulesOfThisType: UniqueNonEmptyList[Group] =
    settings.allLdapGroups

  override protected def userGroups[B <: BlockContext](blockContext: B,
                                                       user: LoggedUser): Task[UniqueList[Group]] =
    settings.ldap.groupsOf(user.id)

  override protected[rules] def mockedGroupsOf(user: User.Id)
                                              (implicit requestId: RequestId,
                                               eq: Eq[User.Id]): Groups = {
    mocksProvider
      .ldapServiceWith(settings.ldap.id)
      .map { mock =>
        Groups.Present(UniqueList.of(
          mock
            .users
            .filter(_.id === user)
            .flatMap { _.groups }
            .toSeq: _*
        ))
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
                            permittedGroups: UniqueNonEmptyList[Group],
                            allLdapGroups: UniqueNonEmptyList[Group])
}