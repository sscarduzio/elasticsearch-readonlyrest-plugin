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
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.NoAuthenticationImpersonationSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.{BaseBasicAuthenticationRule, Rule}
import tech.beshu.ror.accesscontrol.domain.Credentials
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality

final class ExternalAuthenticationRule(val settings: ExternalAuthenticationRule.Settings,
                                       implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseBasicAuthenticationRule
    with NoAuthenticationImpersonationSupport {

  override val name: Rule.Name = ExternalAuthenticationRule.Name.name

  override protected def authenticateUsing(credentials: Credentials): Task[Boolean] =
    settings.service.authenticate(credentials)

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
}

object ExternalAuthenticationRule {

  implicit case object Name extends RuleName[ExternalAuthenticationRule] {
    override val name = Rule.Name("external_authentication")
  }

  final case class Settings(service: ExternalAuthenticationService)
}