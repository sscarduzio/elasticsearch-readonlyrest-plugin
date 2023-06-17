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

import cats.data.EitherT
import cats.implicits._
import com.unboundid.ldap.sdk.{LDAPBindException, ResultCode, SimpleBindRequest}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthenticationService, LdapService, LdapUser}
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.utils.LoggerOps.toLoggerOps
import tech.beshu.ror.utils.ScalaOps._

import scala.concurrent.duration.FiniteDuration

class UnboundidLdapAuthenticationService private(override val id: LdapService#Id,
                                                 connectionPool: UnboundidLdapConnectionPool,
                                                 userSearchFiler: UserSearchFilterConfig,
                                                 override val serviceTimeout: FiniteDuration Refined Positive)
  extends BaseUnboundidLdapService(connectionPool, userSearchFiler, serviceTimeout)
    with LdapAuthenticationService {

  override def authenticate(user: User.Id, secret: PlainTextSecret): Task[Boolean] = {
    ldapUserBy(user)
      .flatMap {
        case Some(ldapUser) =>
          ldapAuthenticate(ldapUser, secret)
        case None =>
          Task.now(false)
      }
  }

  private def ldapAuthenticate(user: LdapUser, password: PlainTextSecret) = {
    connectionPool
      .asyncBind(new SimpleBindRequest(user.dn.value.value, password.value.value))
      .map(_.getResultCode == ResultCode.SUCCESS)
      .onError { case ex =>
        Task(logger.errorEx(s"LDAP authenticate operation failed - cause [${ex.getMessage}]", ex))
      }
      .recover {
        case ex: LDAPBindException if ex.getResultCode == ResultCode.INVALID_CREDENTIALS =>
          false
      }
  }
}
object UnboundidLdapAuthenticationService {
  def create(id: LdapService#Id,
             poolProvider: UnboundidLdapConnectionPoolProvider,
             connectionConfig: LdapConnectionConfig,
             userSearchFiler: UserSearchFilterConfig): Task[Either[ConnectionError, UnboundidLdapAuthenticationService]] = {
    (for {
      _ <- EitherT(UnboundidLdapConnectionPoolProvider.testBindingForAllHosts(connectionConfig))
        .recoverWith {
          case error: ConnectionError =>
            EitherT.cond(
              test = connectionConfig.ignoreLdapConnectivityProblems,
              right = (),
              left = error
            )
        }
      connectionPool <- EitherT.right[ConnectionError](poolProvider.connect(connectionConfig))
    } yield new UnboundidLdapAuthenticationService(
      id,
      connectionPool,
      userSearchFiler,
      serviceTimeout = connectionConfig.connectionTimeout + connectionConfig.requestTimeout
    )).value
  }
}