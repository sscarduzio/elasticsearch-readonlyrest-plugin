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
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.request.RequestContext
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

  override protected def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task
      .unit
      .map { _ =>
        val requestContext = blockContext.requestContext
        if (verifyTokenFromHeader(requestContext)) {
          Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(settings.user))))
        } else {
          Rejected()
        }
      }

  private def verifyTokenFromHeader(requestContext: RequestContext) = {
    requestContext
      .headers
      .find(_.name === settings.tokenHeaderName)
      .exists(_.value == settings.token.value)
  }
}

object TokenAuthenticationRule {
  implicit case object Name extends RuleName[TokenAuthenticationRule] {
    override val name: Rule.Name = Rule.Name("token_authentication")
  }

  final case class Settings(user: User.Id,
                            token: Token,
                            tokenHeaderName: Header.Name)

  object Settings {
    def apply(user: User.Id,
              token: Token,
              customHeaderName: Option[Header.Name]): Settings = {
      Settings(
        user = user,
        token = token,
        tokenHeaderName = customHeaderName.getOrElse(Header.Name.authorization)
      )
    }
  }
}
