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
package tech.beshu.ror.accesscontrol.blocks.rules.auth.base

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Credentials
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.show.logs._

private [auth] abstract class BaseBasicAuthAuthenticationRule
  extends BaseAuthenticationRule
    with Logging {

  protected def authenticateUsing(credentials: Credentials)
                                 (implicit requestId: RequestId): Task[Boolean]

  override def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task
      .unit
      .flatMap { _ =>
        val requestContext = blockContext.requestContext
        implicit val requestId: RequestId = requestContext.id.toRequestId
        requestContext.basicAuth.map(_.credentials) match {
          case Some(credentials) =>
            logger.debug(s"[${requestId.show}] Attempting Login as: ${credentials.user.show}")
            authenticateUsing(credentials)
              .map {
                case true => Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(credentials.user))))
                case false => Rejected()
              }
          case None =>
            logger.debug(s"[${requestId.show}] No basic auth")
            Task.now(Rejected())
        }
      }
}

abstract class BasicAuthenticationRule[CREDENTIALS](val settings: Settings[CREDENTIALS])
  extends BaseBasicAuthAuthenticationRule {

  override protected def authenticateUsing(credentials: Credentials)
                                          (implicit requestId: RequestId): Task[Boolean] =
    compare(settings.credentials, credentials)

  protected def compare(configuredCredentials: CREDENTIALS, credentials: Credentials): Task[Boolean]
}

object BasicAuthenticationRule {

  final case class Settings[CREDENTIALS](credentials: CREDENTIALS)

}