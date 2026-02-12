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

import cats.implicits.toShow
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDefForAuthentication
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseJwtRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthenticationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.{ClaimSearchResult, toClaimsOps}
import tech.beshu.ror.implicits.*

final class JwtAuthenticationRule(val settings: Settings,
                                  override val userIdCaseSensitivity: CaseSensitivity)
  extends AuthenticationRule
    with AuthenticationImpersonationCustomSupport
    with BaseJwtRule {

  override val name: Rule.Name = JwtAuthenticationRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  override protected[rules] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    processUsingJwtToken(blockContext, settings.jwt, AuthenticationFailed.apply) { payload =>
      authenticate(blockContext, payload)
    }
  }

  override protected[rules] def postAuthenticateAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    doPostAuthAction(blockContext, settings.jwt, AuthenticationFailed.apply)
  }

  private def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                    payload: Jwt.Payload) = {
    val result = payload.claims.userIdClaim(settings.jwt.userClaim)
    logClaimSearchResults(blockContext, result)
    result match {
      case Found(userId) =>
        Right(blockContext.withBlockMetadata(
          _.withLoggedUser(DirectlyLoggedUser(userId))
            .withJwtToken(payload)
        ))
      case NotFound =>
        Left(Cause.AuthenticationFailed(s"User claim '${settings.jwt.userClaim.name.show}' not found in JWT"))
    }
  }

  private def logClaimSearchResults[B <: BlockContext](blockContext: B,
                                                       user: ClaimSearchResult[User.Id]): Unit = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    logger.debug(s"[${requestId.show}] JWT resolved user for claim ${settings.jwt.userClaim.name.show}: ${user.show}")
  }

}

object JwtAuthenticationRule {

  implicit case object Name extends RuleName[JwtAuthenticationRule] {
    override val name = Rule.Name("jwt_authentication")
  }

  final case class Settings(jwt: JwtDefForAuthentication)
}
