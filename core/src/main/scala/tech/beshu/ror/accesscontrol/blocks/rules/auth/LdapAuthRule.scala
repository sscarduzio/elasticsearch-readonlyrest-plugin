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

import tech.beshu.ror.accesscontrol.blocks.rules.base.{BaseComposedAuthenticationAndAuthorizationRule, Rule}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality

final class LdapAuthRule(val authentication: LdapAuthenticationRule,
                         val authorization: LdapAuthorizationRule)
  extends BaseComposedAuthenticationAndAuthorizationRule(authentication, authorization) {

  override val name: Rule.Name = LdapAuthRule.Name.name

  override val eligibleUsers: AuthenticationRule.EligibleUsersSupport = authentication.eligibleUsers
  override val caseMappingEquality: UserIdCaseMappingEquality = authentication.caseMappingEquality
}

object LdapAuthRule {
  implicit case object Name extends RuleName[LdapAuthRule] {
    override val name = Rule.Name("ldap_auth")
  }
}