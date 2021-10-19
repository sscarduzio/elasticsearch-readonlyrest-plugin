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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapAuthenticationService
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.ImpersonationSettingsBasedSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.base.{BaseBasicAuthAuthenticationRule, Rule}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Credentials, User}

final class LdapAuthenticationRule(val settings: Settings,
                                   override val impersonation: Impersonation,
                                   implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseBasicAuthAuthenticationRule {

  override val name: Rule.Name = LdapAuthenticationRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  override protected def authenticateUsing(credentials: Credentials): Task[Boolean] =
    settings.ldap.authenticate(credentials.user, credentials.secret)

  override protected[rules] def exists(user: User.Id)
                                      (implicit requestId: RequestId,
                                       eq: Eq[User.Id]): Task[UserExistence] = Task.delay {
    impersonation match {
      case Impersonation.Enabled(impersonationSettings) =>
        impersonationSettings
          .mocksProvider
          .ldapServiceWith(settings.ldap.id)
          .map { mock =>
            val ldapUserExists = mock.users.exists(_.id === user)
            if (ldapUserExists) UserExistence.Exists
            else UserExistence.NotExist
          }
          .getOrElse {
            UserExistence.CannotCheck
          }
      case Impersonation.Disabled =>
        UserExistence.CannotCheck
    }
  }
}

object LdapAuthenticationRule {

  implicit case object Name extends RuleName[LdapAuthenticationRule] {
    override val name = Rule.Name("ldap_authentication")
  }

  final case class Settings(ldap: LdapAuthenticationService)
}
