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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{LoggedUser, User}
import tech.beshu.ror.accesscontrol.request.RorSessionCookie
import tech.beshu.ror.accesscontrol.request.RorSessionCookie.{ExtractingError, toSessionHeader}
import tech.beshu.ror.providers.UuidProvider

import java.time.{Clock, Instant}
import scala.concurrent.duration.FiniteDuration

final class SessionMaxIdleRule(val settings: Settings)
                              (implicit clock: Clock,
                               uuidProvider: UuidProvider,
                               userIdEq: Eq[User.Id])
  extends RegularRule with Logging {

  override val name: Rule.Name = SessionMaxIdleRule.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    blockContext.userMetadata.loggedUser match {
      case Some(user) =>
        checkCookieFor(user, blockContext)
      case None =>
        logger.warn("Cannot state the logged in user, put the authentication rule on top of the block!")
        Rejected()
    }
  }

  private def checkCookieFor[B <: BlockContext : BlockContextUpdater](user: LoggedUser,
                                                                      blockContext: B): RuleResult[B] = {
    RorSessionCookie.extractFrom(blockContext.requestContext, user) match {
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
