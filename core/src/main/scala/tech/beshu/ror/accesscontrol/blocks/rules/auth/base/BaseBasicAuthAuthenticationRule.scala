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

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Permitted
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Credentials, RequestId}
import tech.beshu.ror.accesscontrol.request.RequestContextOps.*

private [auth] abstract class BaseBasicAuthAuthenticationRule
  extends BaseAuthenticationRule {

  protected def authenticateUsing(credentials: Credentials)
                                 (implicit requestId: RequestId): Task[Either[AuthenticationFailed.type, DirectlyLoggedUser]]

  override def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    Task
      .unit
      .flatMap { _ =>
        val requestContext = blockContext.requestContext
        implicit val requestId: RequestId = requestContext.id.toRequestId
        requestContext.basicAuth.map(_.credentials) match {
          case Some(credentials) =>
            authenticateUsing(credentials)
              .map {
                case Right(user) => Permitted(blockContext.withUserMetadata(_.withLoggedUser(user)))
                case Left(AuthenticationFailed) => reject()
              }
          case None =>
            Task.now(reject())
        }
      }
  }

  private def reject[T]() = Decision.Denied[T](Cause.AuthenticationFailed)
}

abstract class BasicAuthenticationRule[CREDENTIALS](val settings: Settings[CREDENTIALS])
  extends BaseBasicAuthAuthenticationRule {

  override protected def authenticateUsing(credentials: Credentials)
                                          (implicit requestId: RequestId): Task[Either[AuthenticationFailed.type, DirectlyLoggedUser]] = {
    compare(settings.credentials, credentials)
      .map {
        case true => Right(DirectlyLoggedUser(credentials.user))
        case false => Left(AuthenticationFailed)
      }
  }

  protected def compare(configuredCredentials: CREDENTIALS, credentials: Credentials): Task[Boolean]
}

object BasicAuthenticationRule {

  final case class Settings[CREDENTIALS](credentials: CREDENTIALS)

}