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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.Prefix
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.request.RequestContextOps.from
import tech.beshu.ror.syntax.*

final class TokenAuthenticationRule(val settings: Settings,
                                    override implicit val userIdCaseSensitivity: CaseSensitivity,
                                    override val impersonation: Impersonation)
  extends BaseAuthenticationRule
    with Logging {

  override val name: Rule.Name = TokenAuthenticationRule.Name.name

  override val eligibleUsers: AuthenticationRule.EligibleUsersSupport = EligibleUsersSupport.Available(Set(settings.user))

  override def exists(user: User.Id, mocksProvider: MocksProvider)
                     (implicit requestId: RequestId): Task[UserExistence] = Task.now {
    if (user === settings.user) UserExistence.Exists
    else UserExistence.NotExist
  }

  override protected def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    val requestContext = blockContext.requestContext
    val verification = settings.token match {
      case TokenDefinition.StaticToken(configuredToken) =>
        Task.delay {
          requestContext
            .authorizationToken(AuthorizationTokenDef(settings.tokenHeaderName, Prefix.Any))
            .exists { token => token.value == configuredToken } // todo: prefix not verified
        }
      case TokenDefinition.DynamicToken =>
        requestContext.authorizationToken(AuthorizationTokenDef(settings.tokenHeaderName, Prefix.Exact("Bearer"))) match {
          case None => Task.now(false)
          case Some(token) => requestContext.serviceAccountTokenService.validateToken(token)
        }
    }
    verification.map {
      case true => Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(settings.user))))
      case false => Rejected()
    }
  }
}

object TokenAuthenticationRule {
  implicit case object Name extends RuleName[TokenAuthenticationRule] {
    override val name: Rule.Name = Rule.Name("token_authentication")
  }

  final case class Settings(user: User.Id,
                            token: TokenDefinition,
                            tokenHeaderName: Header.Name)

  object Settings {
    def apply(user: User.Id,
              token: TokenDefinition,
              customHeaderName: Option[Header.Name]): Settings = {
      Settings(
        user = user,
        token = token,
        tokenHeaderName = customHeaderName.getOrElse(Header.Name.authorization)
      )
    }
  }
}
