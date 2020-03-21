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

import java.time.{Clock, Instant}

import org.apache.logging.log4j.scala.Logging
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.{LoggedUser, Operation}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.accesscontrol.request.RorSessionCookie.{ExtractingError, toSessionHeader}
import tech.beshu.ror.accesscontrol.request.{RequestContext, RorSessionCookie}
import tech.beshu.ror.providers.UuidProvider

import scala.concurrent.duration.FiniteDuration

class SessionMaxIdleRule(val settings: Settings)
                        (implicit clock: Clock, uuidProvider: UuidProvider)
  extends RegularRule with Logging {

  override val name: Rule.Name = SessionMaxIdleRule.name

  override def check[T <: Operation](requestContext: RequestContext[T],
                                     blockContext: BlockContext[T]): Task[RuleResult[T]] = Task {
    blockContext.loggedUser match {
      case Some(user) =>
        checkCookieFor(user, requestContext, blockContext)
      case None =>
        logger.warn("Cannot state the logged in user, put the authentication rule on top of the block!")
        Rejected()
    }
  }

  private def checkCookieFor[T <: Operation](user: LoggedUser,
                                             requestContext: RequestContext[T],
                                             blockContext: BlockContext[T]) = {
    RorSessionCookie.extractFrom(requestContext, user) match {
      case Right(_) | Left(ExtractingError.Absent) =>
        val newCookie = RorSessionCookie(user.id, newExpiryDate)
        Fulfilled(blockContext.withAddedResponseHeader(toSessionHeader(newCookie)))
      case Left(ExtractingError.Invalid) | Left(ExtractingError.Expired) =>
        Rejected()
    }
  }

  private def newExpiryDate = Instant.now(clock).plusMillis(settings.sessionMaxIdle.value.toMillis)
}

object SessionMaxIdleRule {
  val name = Rule.Name("session_max_idle")

  final case class Settings(sessionMaxIdle: FiniteDuration Refined Positive)

}
