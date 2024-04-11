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

import cats.implicits._
import com.unboundid.ldap.sdk.{LDAPBindException, ResultCode, SimpleBindRequest}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthenticationService, LdapService, LdapUser, LdapUsersService}
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.TaskOps._

import java.time.Clock

class UnboundidLdapAuthenticationService private(override val id: LdapService#Id,
                                                 override val ldapUsersService: LdapUsersService,
                                                 connectionPool: UnboundidLdapConnectionPool,
                                                 override val serviceTimeout: PositiveFiniteDuration)
                                                (implicit clock: Clock)
  extends LdapAuthenticationService with Logging {

  override def authenticate(user: User.Id, secret: PlainTextSecret)(implicit requestId: RequestId): Task[Boolean] = {
    Task.measure(
      doAuthenticate(user, secret),
      measurement => Task.delay {
        logger.debug(s"[${requestId.show}] LDAP authentication took $measurement")
      }
    )
  }

  private def doAuthenticate(user: User.Id, secret: PlainTextSecret)(implicit requestId: RequestId) = {
    ldapUsersService
      .ldapUserBy(user)
      .flatMap {
        case Some(ldapUser) =>
          ldapAuthenticate(ldapUser, secret)
        case None =>
          Task.now(false)
      }
  }

  private def ldapAuthenticate(user: LdapUser, password: PlainTextSecret)(implicit requestId: RequestId) = {
    logger.debug(s"[${requestId.show}] LDAP simple bind [user DN: ${user.dn.value.value}]")
    connectionPool
      .asyncBind(new SimpleBindRequest(user.dn.value.value, password.value.value))
      .map(_.getResultCode == ResultCode.SUCCESS)
      .onError { case ex =>
        Task(logger.error(s"[${requestId.show}] LDAP authenticate operation failed - cause [${ex.getMessage}]", ex))
      }
      .recover {
        case ex: LDAPBindException if ex.getResultCode == ResultCode.INVALID_CREDENTIALS =>
          false
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