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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import com.unboundid.ldap.sdk.{LDAPBindException, ResultCode, SimpleBindRequest}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapAuthenticationService.AuthenticationResult
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthenticationService, LdapService, LdapUser, LdapUsersService}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, RequestId, User}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.TaskOps.*

import java.time.Clock

class UnboundidLdapAuthenticationService private(override val id: LdapService#Id,
                                                 override val ldapUsersService: LdapUsersService,
                                                 connectionPool: UnboundidLdapConnectionPool,
                                                 override val serviceTimeout: PositiveFiniteDuration)
                                                (implicit clock: Clock)
  extends LdapAuthenticationService with RequestIdAwareLogging {

  override def authenticate(user: User.Id, secret: PlainTextSecret)
                           (implicit requestId: RequestId): Task[AuthenticationResult] = {
    Task.measure(
      doAuthenticate(user, secret),
      measurement => Task.delay {
        logger.debug(s"LDAP authentication took ${measurement.show}")
      }
    )
  }

  private def doAuthenticate(user: User.Id, secret: PlainTextSecret)
                            (implicit requestId: RequestId) = {
    ldapUsersService
      .ldapUserBy(user)
      .flatMap {
        case Some(ldapUser) =>
          ldapAuthenticate(ldapUser, secret)
        case None =>
          Task.now(Left(AuthenticationFailed("User not found in LDAP")))
      }
  }

  private def ldapAuthenticate(user: LdapUser, password: PlainTextSecret)
                              (implicit requestId: RequestId) = {
    logger.debug(s"LDAP simple bind [user DN: ${user.dn.show}]")
    connectionPool
      .asyncBind(new SimpleBindRequest(user.dn.value.value, password.value.value))
      .map(_.getResultCode == ResultCode.SUCCESS)
      .map {
        case true => Right(DirectlyLoggedUser(user.id))
        case false => Left(AuthenticationFailed("LDAP bind failed"))
      }
      .onError { case ex =>
        Task(logger.error(s"LDAP authenticate operation failed - cause [${ex.getMessage.show}]", ex))
      }
      .recover {
        case ex: LDAPBindException if ex.getResultCode == ResultCode.INVALID_CREDENTIALS =>
          Left(AuthenticationFailed("Invalid credentials"))
      }
  }
}
object UnboundidLdapAuthenticationService {
  def create(id: LdapService#Id,
             ldapUsersService: LdapUsersService,
             poolProvider: UnboundidLdapConnectionPoolProvider,
             connectionConfig: LdapConnectionConfig)
            (implicit clock: Clock): Task[Either[ConnectionError, UnboundidLdapAuthenticationService]] = {
    UnboundidLdapConnectionPoolProvider
      .connectWithOptionalBindingTest(poolProvider, connectionConfig)
      .map(_.map(connectionPool =>
        new UnboundidLdapAuthenticationService(
          id = id,
          ldapUsersService = ldapUsersService,
          connectionPool = connectionPool,
          serviceTimeout = connectionConfig.requestTimeout
        )
      ))
  }
}