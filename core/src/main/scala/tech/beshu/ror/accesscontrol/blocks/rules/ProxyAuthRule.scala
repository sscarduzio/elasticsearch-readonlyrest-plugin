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

import cats.implicits._
import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.ProxyAuthRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, NoImpersonationSupport, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.stringUserIdNT
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain.{Header, LoggedUser, User}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class ProxyAuthRule(val settings: Settings)
  extends AuthenticationRule
    with NoImpersonationSupport
    with Logging {

  private val userMatcher = new MatcherWithWildcardsScalaAdapter(
    new MatcherWithWildcards(settings.userIds.toSortedSet.map(_.value.value).asJava)
  )

  override val name: Rule.Name = ProxyAuthRule.name

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
  val name = Rule.Name("proxy_auth")

  final case class Settings(userIds: NonEmptySet[User.Id], userHeaderName: Header.Name)
}