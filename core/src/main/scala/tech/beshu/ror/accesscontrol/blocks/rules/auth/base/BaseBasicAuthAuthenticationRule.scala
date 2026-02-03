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

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Credentials, RequestId}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps.*

private[auth] abstract class BaseBasicAuthAuthenticationRule
  extends BaseAuthenticationRule {

  protected def authenticateUsing(credentials: Credentials)
                                 (implicit requestId: RequestId): Task[Either[AuthenticationFailed, DirectlyLoggedUser]]

  override def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    Task
      .unit
      .flatMap { _ =>
        implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
        val result = for {
          credentials <- basicAuthCredentialsFrom(blockContext.requestContext)
          loggedUser <- EitherT(authenticateUsing(credentials))
        } yield blockContext.withUserMetadata(_.withLoggedUser(loggedUser))
        result.toDecision
      }
  }

  private def basicAuthCredentialsFrom(requestContext: RequestContext) = {
    EitherT.fromEither[Task] {
      requestContext
        .basicAuth.map(_.credentials)
        .toRight(AuthenticationFailed("No basic auth credentials provided"))
    }
  }
}

abstract class BasicAuthenticationRule[CREDENTIALS](val settings: Settings[CREDENTIALS])
  extends BaseBasicAuthAuthenticationRule {

  override protected def authenticateUsing(credentials: Credentials)
                                          (implicit requestId: RequestId): Task[Either[AuthenticationFailed, DirectlyLoggedUser]] =
    compare(settings.credentials, credentials)

  protected def compare(configuredCredentials: CREDENTIALS, credentials: Credentials): Task[Either[AuthenticationFailed, DirectlyLoggedUser]]
}

object BasicAuthenticationRule {

  final case class Settings[CREDENTIALS](credentials: CREDENTIALS)

}