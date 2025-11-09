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

import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseComposedAuthenticationAndAuthorizationRule
import tech.beshu.ror.accesscontrol.domain.*

final class JwtAuthRule(val authentication: JwtAuthenticationRule,
                        val authorization: JwtAuthorizationRule | JwtPseudoAuthorizationRule)
  extends BaseComposedAuthenticationAndAuthorizationRule(
    authenticationRule = authentication.withDisabledCallsToExternalAuthenticationService,
    authorizationRule = authorization
  ) {

  override val name: Rule.Name = JwtAuthRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
  override val userIdCaseSensitivity: CaseSensitivity = authentication.userIdCaseSensitivity
}

object JwtAuthRule {
  implicit case object Name extends RuleName[JwtAuthRule] {
    override val name = Rule.Name("jwt_auth")
  }
}