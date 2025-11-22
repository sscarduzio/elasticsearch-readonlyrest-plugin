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
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ProxyAuthRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, Header, RequestId, User}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final class ProxyAuthRule(val settings: Settings,
                          override implicit val userIdCaseSensitivity: CaseSensitivity,
                          override val impersonation: Impersonation)
  extends BaseAuthenticationRule
    with RequestIdAwareLogging {

  private val userMatcher = PatternsMatcher.create(settings.userIds)

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.Available(settings.userIds.toCovariantSet)

  override val name: Rule.Name = ProxyAuthRule.Name.name

  override def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    getLoggedUser(blockContext.requestContext) match {
      case None =>
        Rejected()
      case Some(loggedUser) if shouldAuthenticate(loggedUser.id) =>
        Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(loggedUser)))
      case Some(_) =>
        Rejected()
    }
  }

  override protected[rules] def exists(user: User.Id, mocksProvider: MocksProvider)
                                      (implicit requestId: RequestId): Task[UserExistence] = Task.delay {
    if (shouldAuthenticate(user)) UserExistence.Exists
    else UserExistence.NotExist
  }

  private def getLoggedUser(context: RequestContext) = {
    context
      .restRequest.allHeaders
      .find(_.name === settings.userHeaderName)
      .map(h => DirectlyLoggedUser(Id(h.value)))
  }

  private def shouldAuthenticate(userId: User.Id) = {
    userMatcher.`match`(userId)
  }
}

object ProxyAuthRule {

  implicit case object Name extends RuleName[ProxyAuthRule] {
    override val name = Rule.Name("proxy_auth")
  }

  final case class Settings(userIds: UniqueNonEmptyList[User.Id], userHeaderName: Header.Name)
}