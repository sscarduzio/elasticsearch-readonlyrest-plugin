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
package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.domain.{BasicAuth, LoggedUser, Secret}
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BasicAuthenticationRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._

abstract class BaseBasicAuthenticationRule
  extends AuthenticationRule
    with Logging {

  protected def authenticate(basicAuth: BasicAuth): Task[Boolean]

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] =
    Task
      .unit
      .flatMap { _ =>
        requestContext.basicAuth match {
          case Some(credentials) =>
            logger.debug(s"Attempting Login as: ${credentials.user.show} rc: ${requestContext.id.show}")
            authenticate(credentials)
              .map {
                case true => Fulfilled(blockContext.withLoggedUser(LoggedUser(credentials.user)))
                case false => Rejected
              }
          case None =>
            logger.debug(s"No basic auth, rc: ${requestContext.id.show}")
            Task.now(Rejected)
        }
      }
}

abstract class BasicAuthenticationRule(val settings: Settings)
  extends BaseBasicAuthenticationRule {

  override protected def authenticate(basicAuth: BasicAuth): Task[Boolean] =
    compare(settings.authKey, basicAuth)

  protected def compare(configuredAuthKey: Secret, basicAuth: BasicAuth): Task[Boolean]
}

object BasicAuthenticationRule {

  final case class Settings(authKey: Secret)

}