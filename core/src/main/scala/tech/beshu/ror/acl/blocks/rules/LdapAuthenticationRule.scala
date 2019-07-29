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
package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapAuthenticationService
import tech.beshu.ror.acl.blocks.rules.LdapAuthenticationRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule.UserExistence
import tech.beshu.ror.acl.blocks.rules.Rule.NoImpersonationSupport
import tech.beshu.ror.acl.domain.{Credentials, User}

class LdapAuthenticationRule(val settings: Settings)
  extends BaseBasicAuthenticationRule
    with NoImpersonationSupport {

  override val name: Rule.Name = LdapAuthenticationRule.name

  override protected def authenticateUsing(credentials: Credentials): Task[Boolean] =
    settings.ldap.authenticate(credentials.user, credentials.secret)

  override def exists(user: User.Id): Task[UserExistence] = Task.now(UserExistence.CannotCheck)
}


object LdapAuthenticationRule {
  val name = Rule.Name("ldap_authentication")

  final case class Settings(ldap: LdapAuthenticationService)
}
