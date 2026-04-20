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
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseRorKbnRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthenticationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.Found

final class RorKbnAuthenticationRule(val settings: Settings,
                                     override val userIdCaseSensitivity: CaseSensitivity)
  extends AuthenticationRule
    with AuthenticationImpersonationCustomSupport
    with BaseRorKbnRule {

  override val name: Rule.Name = RorKbnAuthenticationRule.Name.name

  override val localUsers: LocalUsers = LocalUsers.NotAvailable

  override protected[rules] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = Task.delay {
    processUsingJwtToken(blockContext, settings.rorKbn) { tokenData =>
      authenticate(blockContext, tokenData.userId, tokenData.userOrigin, tokenData.payload)
    }
  }

  private def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                    userIdFromToken: ClaimSearchResult[User.Id],
                                                                    userOriginFromToken: ClaimSearchResult[Header],
                                                                    tokenPayload: Jwt.Payload): Either[Cause, B] = {
    for {
      userId <- userIdFromToken.toEither.left.map { case () => AuthenticationFailed("User claim not found in ROR Kibana token") }
      updatedBlockContext = userOriginFromToken match {
        case Found(header) =>
          blockContext.withBlockMetadata(
            _
              .withLoggedUser(DirectlyLoggedUser(userId))
              .withUserOrigin(UserOrigin(header.value))
              .withJwtToken(tokenPayload)
          )
        case ClaimSearchResult.NotFound =>
          blockContext.withBlockMetadata(
            _
              .withLoggedUser(DirectlyLoggedUser(userId))
              .withJwtToken(tokenPayload)
          )
      }
    } yield updatedBlockContext
  }

}

object RorKbnAuthenticationRule {

  implicit case object Name extends RuleName[RorKbnAuthenticationRule] {
    override val name = Rule.Name("ror_kbn_authentication")
  }

  final case class Settings(rorKbn: RorKbnDef)
}
