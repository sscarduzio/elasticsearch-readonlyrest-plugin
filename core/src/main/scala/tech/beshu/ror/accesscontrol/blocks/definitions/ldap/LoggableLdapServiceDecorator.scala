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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.TaskOps._

import scala.util.{Failure, Success}

class LoggableLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService)
  extends LdapAuthenticationService
    with Logging {

  private val loggableLdapUserService = new LoggableLdapUserServiceDecorator(underlying)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    loggableLdapUserService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret): Task[Boolean] = {
    logger.debug(s"Trying to authenticate user [${user.show}] with LDAP [${id.show}]")
    underlying
      .authenticate(user, secret)
      .andThen {
        case Success(authenticationResult) =>
          logger.debug(s"User [${user.show}] ${if (authenticationResult) "" else "not"} authenticated by LDAP [${id.show}]")
        case Failure(ex) =>
          logger.debug(s"LDAP authentication failed:", ex)
      }
  }
}

class LoggableLdapAuthorizationServiceDecorator(underlying: LdapAuthorizationService)
  extends LdapAuthorizationService
    with Logging {

  private val loggableLdapUserService = new LoggableLdapUserServiceDecorator(underlying)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    loggableLdapUserService.ldapUserBy(userId)

  override def groupsOf(userId: User.Id): Task[Set[domain.Group]] = {
    logger.debug(s"Trying to fetch user [id=${userId.show}] groups from LDAP [${id.show}]")
    underlying
      .groupsOf(userId)
      .andThen {
        case Success(groups) =>
          logger.debug(s"LDAP [${id.show}] returned for user [${userId.show}] following groups: [${groups.map(_.show).mkString(",")}]")
        case Failure(ex) =>
          logger.debug(s"Fetching LDAP user's groups failed:", ex)
      }
  }
}

class LoggableLdapServiceDecorator(underlying: LdapAuthService)
  extends LdapAuthService {

  private val loggableLdapAuthenticationService = new LoggableLdapAuthenticationServiceDecorator(underlying)
  private val loggableLdapAuthorizationService = new LoggableLdapAuthorizationServiceDecorator(underlying)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    loggableLdapAuthenticationService.ldapUserBy(userId)

  override def authenticate(userId: User.Id, secret: domain.PlainTextSecret): Task[Boolean] =
    loggableLdapAuthenticationService.authenticate(userId, secret)

  override def groupsOf(userId: User.Id): Task[Set[domain.Group]] =
    loggableLdapAuthorizationService.groupsOf(userId)
}

private class LoggableLdapUserServiceDecorator(underlying: LdapUserService)
  extends LdapUserService
    with Logging {

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    logger.debug(s"Trying to fetch user with identifier [${userId.show}] from LDAP [${id.show}]")
    underlying
      .ldapUserBy(userId)
      .andThen {
        case Success(ldapUser) =>
          ldapUser match {
            case Some(user) => logger.debug(s"User with identifier [${userId.show}] found [dn = ${user.dn.show}]")
            case None => logger.debug(s"User with identifier [${userId.show}] not found")
          }
        case Failure(ex) =>
          logger.debug(s"Fetching LDAP user failed:", ex)
      }
  }
}