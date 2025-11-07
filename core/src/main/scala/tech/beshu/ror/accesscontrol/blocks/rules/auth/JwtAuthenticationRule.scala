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

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseJwtRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthenticationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}

final class JwtAuthenticationRule(val settings: Settings,
                                  override val userIdCaseSensitivity: CaseSensitivity)
  extends AuthenticationRule
    with AuthenticationImpersonationCustomSupport
    with BaseJwtRule {

  override val name: Rule.Name = JwtAuthenticationRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  override protected[rules] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    processUsingJwtToken(blockContext, settings.jwt) { tokenData =>
      authenticate(blockContext, tokenData.userId, tokenData.payload)
    }.flatMap(finalize(_, settings.jwt))
  }

  private def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                    result: Option[ClaimSearchResult[User.Id]],
                                                                    payload: Jwt.Payload) = {
    (result match {
      case None => Right(blockContext)
      case Some(NotFound) => Left(())
      case Some(Found(userId)) => Right(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(userId))))
    }).map(_.withUserMetadata(_.withJwtToken(payload)))
  }

}

object JwtAuthenticationRule {

  implicit case object Name extends RuleName[JwtAuthenticationRule] {
    override val name = Rule.Name("jwt_authentication")
  }

  final case class Settings(jwt: JwtDef)
}
