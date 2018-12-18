package tech.beshu.ror.acl.blocks.rules

import java.time.{Clock, Instant}

import com.typesafe.scalalogging.StrictLogging
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.acl.request.RorSessionCookie.{ExtractingError, emptySessionHeader, toSessionHeader}
import tech.beshu.ror.acl.request.{RequestContext, RorSessionCookie}
import tech.beshu.ror.acl.utils.UuidProvider
import tech.beshu.ror.commons.domain.LoggedUser

import scala.concurrent.duration.FiniteDuration

class SessionMaxIdleRule(settings: Settings)
                        (implicit clock: Clock, uuidProvider: UuidProvider)
  extends RegularRule with StrictLogging {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    context.loggedUser match {
      case Some(user) =>
        checkCookieFor(user, context)
      case None =>
        logger.warn("Cannot state the logged in user, put the authentication rule on top of the block!")
        context.setResponseHeader(emptySessionHeader)
        false
    }
  }

  private def checkCookieFor(user: LoggedUser, context: RequestContext) = {
    RorSessionCookie.extractFrom(context, user) match {
      case Right(_) | Left(ExtractingError.Absent) =>
        val newCookie = RorSessionCookie(user.id, newExpiryDate)
        context.setResponseHeader(toSessionHeader(newCookie))
        true
      case Left(ExtractingError.Invalid) | Left(ExtractingError.Expired) =>
        context.setResponseHeader(emptySessionHeader)
        false
    }
  }

  private def newExpiryDate = Instant.now(clock).plusMillis(settings.sessionMaxIdle.value.toMillis)
}

object SessionMaxIdleRule {

  final case class Settings(sessionMaxIdle: FiniteDuration Refined Positive)

}
