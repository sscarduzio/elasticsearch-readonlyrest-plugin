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

import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings.TokenType
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

final class TokenAuthenticationRule(val settings: Settings,
                                    override implicit val userIdCaseSensitivity: CaseSensitivity,
                                    override val impersonation: Impersonation)
  extends BaseAuthenticationRule {

  override val name: Rule.Name = TokenAuthenticationRule.Name.name

  override val eligibleUsers: AuthenticationRule.EligibleUsersSupport = EligibleUsersSupport.Available(Set(settings.user), unknownUsers = false)

  override def exists(user: User.Id, mocksProvider: MocksProvider)
                     (implicit requestId: RequestId): Task[UserExistence] = Task.now {
    if (user === settings.user) UserExistence.Exists
    else UserExistence.NotExist
  }

  override protected def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    val verification = settings.tokenType match {
      case tokenType: TokenType.StaticToken => authenticateWithStaticToken(blockContext, tokenType)
    }
    verification.map {
      case true =>
        Permitted(blockContext.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(settings.user))))
      case false =>
        val tokenHeaderName = settings.tokenType match {
          case TokenType.StaticToken(tokenDef, _) => tokenDef.headerName
        }
        Denied(Cause.AuthenticationFailed(s"Token header '${tokenHeaderName.show}' missing or invalid"))
    }
  }

  private def authenticateWithStaticToken(blockContext: BlockContext, tokenType: TokenType.StaticToken) =
    Task.delay {
      blockContext.requestContext
        .authorizationTokenBy(tokenType.tokenDef)
        .contains(tokenType.token)
    }
}

object TokenAuthenticationRule {
  implicit case object Name extends RuleName[TokenAuthenticationRule] {
    override val name: Rule.Name = Rule.Name("token_authentication")
  }

  final case class Settings(user: User.Id, tokenType: TokenType)
  object Settings {
    sealed trait TokenType
    object TokenType {
      final case class StaticToken(tokenDef: AuthorizationTokenDef, token: AuthorizationToken) extends TokenType
    }
  }
}
