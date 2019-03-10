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
import tech.beshu.ror.acl.domain.BasicAuth

class LdapAuthenticationRule(val settings: Settings)
  extends BaseBasicAuthenticationRule {

  override val name: Rule.Name = LdapAuthenticationRule.name

  override protected def authenticate(basicAuthCredentials: BasicAuth): Task[Boolean] =
    settings.ldap
      .authenticate(basicAuthCredentials.user, basicAuthCredentials.secret)
}


object LdapAuthenticationRule {
  val name = Rule.Name("ldap_authentication")

  final case class Settings(ldap: LdapAuthenticationService)
}
