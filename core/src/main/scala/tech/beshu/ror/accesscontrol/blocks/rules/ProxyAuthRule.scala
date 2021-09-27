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

import cats.Eq
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.rules.ProxyAuthRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Header, LoggedUser, User}
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final class ProxyAuthRule(val settings: Settings,
                          override val impersonators: List[ImpersonatorDef],
                          implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthenticationRule
    with Logging {

  private val userMatcher = MatcherWithWildcardsScalaAdapter[User.Id](settings.userIds.toSet)

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.Available(settings.userIds.toSet)

  override val name: Rule.Name = ProxyAuthRule.Name.name

  override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    getLoggedUser(blockContext.requestContext) match {
      case None =>
        Rejected()
      case Some(loggedUser) if shouldAuthenticate(loggedUser) =>
        Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(loggedUser)))
      case Some(_) =>
        Rejected()
    }
  }

  override protected def exists(user: Id)
                               (implicit userIdEq: Eq[Id]): Task[UserExistence] =
    Task.now(UserExistence.Exists)

  private def getLoggedUser(context: RequestContext) = {
    context
      .headers
      .find(_.name === settings.userHeaderName)
      .map(h => DirectlyLoggedUser(Id(h.value)))
  }

  private def shouldAuthenticate(user: LoggedUser) = {
    userMatcher.`match`(user.id)
  }
}

object ProxyAuthRule {

  implicit case object Name extends RuleName[ProxyAuthRule] {
    override val name = Rule.Name("proxy_auth")
  }

  final case class Settings(userIds: UniqueNonEmptyList[User.Id], userHeaderName: Header.Name)
}