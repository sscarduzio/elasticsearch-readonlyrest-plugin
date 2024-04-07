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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, User}
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.TaskOps._
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class LoggableLdapAuthenticationServiceDecorator(val underlying: LdapAuthenticationService)
  extends LdapAuthenticationService
    with Logging {

  override def id: LdapService.Name = underlying.id

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret)(implicit requestId: RequestId): Task[Boolean] = {
    logger.debug(s"[${requestId.show}] Trying to authenticate user [${user.show}] with LDAP [${id.show}]")
    underlying
      .authenticate(user, secret)
      .andThen {
        case Success(authenticationResult) =>
          logger.debug(s"[${requestId.show}] User [${user.show}]${if (authenticationResult) "" else " not"} authenticated by LDAP [${id.show}]")
        case Failure(ex) =>
          logger.debug(s"[${requestId.show}] LDAP authentication failed:", ex)
      }
  }

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

class LoggableLdapAuthorizationServiceDecorator(val underlying: LdapAuthorizationService)
  extends LdapAuthorizationService
    with Logging {

  override def id: LdapService.Name = underlying.id

  override def groupsOf(userId: User.Id)
                       (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    logger.debug(s"[${requestId.show}] Trying to fetch user [id=${userId.show}] groups from LDAP [${id.show}]")
    underlying
      .groupsOf(userId)
      .andThen {
        case Success(groups) =>
          logger.debug(s"LDAP [${id.show}] returned for user [${userId.show}] following groups: [${groups.map(_.show).mkString(",")}]")
        case Failure(ex) =>
          logger.debug(s"Fetching LDAP user's groups failed:", ex)
      }
  }

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

class LoggableLdapAuthorizationServiceWithGroupsFilteringDecorator(val underlying: LdapAuthorizationServiceWithGroupsFiltering)
  extends LdapAuthorizationServiceWithGroupsFiltering
    with Logging {

  override def id: LdapService.Name = underlying.id

  override def groupsOf(userId: User.Id, filteringGroupIds: Set[GroupIdLike])
                       (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    logger.debug(s"Trying to fetch user [id=${userId.show}] groups from LDAP [${id.show}] (assuming that filtered group IDs are [${filteringGroupIds.map(_.show).mkString(",")}])")
    underlying
      .groupsOf(userId, filteringGroupIds)
      .andThen {
        case Success(groups) =>
          logger.debug(s"[${requestId.show}] LDAP [${id.show}] returned for user [${userId.show}] following groups: [${groups.map(_.show).mkString(",")}]")
        case Failure(ex) =>
          logger.debug(s"[${requestId.show}] Fetching LDAP user's groups failed:", ex)
      }
  }

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

class LoggableLdapServiceDecorator(val underlying: LdapAuthService)
  extends LdapAuthService {

  private val loggableLdapAuthenticationService = new LoggableLdapAuthenticationServiceDecorator(underlying)
  private val loggableLdapAuthorizationService = new LoggableLdapAuthorizationServiceWithGroupsFilteringDecorator(underlying)

  override def id: LdapService.Name = underlying.id

  override def authenticate(userId: User.Id, secret: domain.PlainTextSecret)(implicit requestId: RequestId): Task[Boolean] =
    loggableLdapAuthenticationService.authenticate(userId, secret)

  override def groupsOf(userId: User.Id, filteringGroupIds: Set[GroupIdLike])(implicit requestId: RequestId): Task[UniqueList[Group]] =
    loggableLdapAuthorizationService.groupsOf(userId, filteringGroupIds)

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

private class LoggableLdapUsersServiceDecorator(underlying: LdapUsersService)
  extends LdapUsersService
    with Logging {

  override def id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id)(implicit requestId: RequestId): Task[Option[LdapUser]] = {
    logger.debug(s"[${requestId.show}] Trying to fetch user with identifier [${userId.show}] from LDAP [${id.show}]")
    underlying
      .ldapUserBy(userId)
      .andThen {
        case Success(ldapUser) =>
          ldapUser match {
            case Some(user) => logger.debug(s"[${requestId.show}] User with identifier [${userId.show}] found [dn = ${user.dn.show}]")
            case None => logger.debug(s"[${requestId.show}] User with identifier [${userId.show}] not found")
          }
        case Failure(ex) =>
          logger.debug(s"[${requestId.show}] Fetching LDAP user failed:", ex)
      }
  }

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}