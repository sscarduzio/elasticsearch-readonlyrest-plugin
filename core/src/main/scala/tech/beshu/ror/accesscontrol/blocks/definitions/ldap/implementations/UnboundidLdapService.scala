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

import cats.Order
import cats.data.{EitherT, NonEmptyList}
import cats.implicits._
import com.unboundid.ldap.sdk._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.UrlWithAuthority
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.LdapConnectionConfig.{BindRequestUser, ConnectionMethod}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{DefaultGroupSearch, GroupsFromUserAttribute}
import tech.beshu.ror.accesscontrol.domain.{Group, PlainTextSecret, User}
import tech.beshu.ror.accesscontrol.utils.LdapConnectionPoolOps._
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class UnboundidLdapAuthenticationService private(override val id: LdapService#Id,
                                                 connectionPool: LDAPConnectionPool,
                                                 userSearchFiler: UserSearchFilterConfig,
                                                 requestTimeout: FiniteDuration Refined Positive)
                                                (implicit blockingScheduler: Scheduler)
  extends BaseUnboundidLdapService(connectionPool, userSearchFiler, requestTimeout)
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
    Task(connectionPool.getConnection)
      .bracket(
        use = connection => Task(connection.bind(new SimpleBindRequest(user.dn.value.value, password.value.value)))
      )(
        release = connection => Task(connectionPool.releaseAndReAuthenticateConnection(connection))
      )
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
             userSearchFiler: UserSearchFilterConfig,
             blockingScheduler: Scheduler): Task[Either[ConnectionError, UnboundidLdapAuthenticationService]] = {
    implicit val blockingSchedulerImplicit: Scheduler = blockingScheduler
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
      connectionPool <- EitherT.liftF[Task, ConnectionError, LDAPConnectionPool](poolProvider.connect(connectionConfig))
    } yield new UnboundidLdapAuthenticationService(id, connectionPool, userSearchFiler, connectionConfig.requestTimeout)).value
  }
}

class UnboundidLdapAuthorizationService private(override val id: LdapService#Id,
                                                connectionPool: LDAPConnectionPool,
                                                groupsSearchFilter: UserGroupsSearchFilterConfig,
                                                userSearchFiler: UserSearchFilterConfig,
                                                requestTimeout: FiniteDuration Refined Positive)
                                               (implicit blockingScheduler: Scheduler)
  extends BaseUnboundidLdapService(connectionPool, userSearchFiler, requestTimeout)
    with LdapAuthorizationService {

  override def groupsOf(id: User.Id): Task[UniqueList[Group]] = {
    ldapUserBy(id)
      .flatMap {
        case Some(user) =>
          groupsSearchFilter.mode match {
            case defaultSearchGroupMode: DefaultGroupSearch => groupsFrom(defaultSearchGroupMode, user)
            case groupsFromUserAttribute: GroupsFromUserAttribute => groupsFrom(groupsFromUserAttribute, user)
          }
        case None =>
          Task.now(UniqueList.empty)
      }
  }

  private def groupsFrom(defaultSearchGroupMode: DefaultGroupSearch, user: LdapUser): Task[UniqueList[Group]] = {
    val searchFilter = searchFilterFrom(defaultSearchGroupMode, user)
    logger.debug(s"LDAP search string: $searchFilter | groupNameAttr: ${defaultSearchGroupMode.groupNameAttribute}")
    connectionPool
      .process(searchGroupsLdapRequest(_, searchFilter, defaultSearchGroupMode), requestTimeout)
      .flatMap {
        case Right(results) =>
          Task {
            UniqueList.fromList(
              results
                .flatMap { r =>
                  Option(r.getAttributeValue(defaultSearchGroupMode.groupNameAttribute.value))
                    .flatMap(NonEmptyString.unapply)
                }
                .map(Group.apply)
            )
          }
        case Left(errorResult) =>
          logger.error(s"LDAP getting user groups returned error: [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.errorEx(s"LDAP getting user groups returned error", ex))
      }
  }

  private def groupsFrom(mode: GroupsFromUserAttribute, user: LdapUser): Task[UniqueList[Group]] = {
    logger.debug(s"LDAP search string: ${user.dn.value.value} | groupsFromUserAttribute: ${mode.groupsFromUserAttribute.value}")
    connectionPool
      .process(searchUserGroupsLdapRequest(_, user, mode), requestTimeout)
      .flatMap {
        case Right(results) =>
          Task {
            UniqueList
              .fromList(
                results
                  .flatMap { r =>
                    Option(r.getAttributeValues(mode.groupsFromUserAttribute.value))
                      .toList.flatMap(_.toList)
                      .flatMap(groupNameFromDn(_, mode))
                      .flatMap(NonEmptyString.unapply)
                  }
                  .map(Group.apply)
              )
          }
        case Left(errorResult) =>
          logger.error(s"LDAP getting user groups returned error [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.errorEx(s"LDAP getting user groups returned error", ex))
      }
  }

  private def searchFilterFrom(mode: DefaultGroupSearch, user: LdapUser) = {
    if (mode.groupAttributeIsDN) s"(&${mode.groupSearchFilter}(${mode.uniqueMemberAttribute}=${Filter.encodeValue(user.dn.value.value)}))"
    else s"(&${mode.groupSearchFilter}(${mode.uniqueMemberAttribute}=${user.id.value}))"
  }

  private def searchGroupsLdapRequest(listener: AsyncSearchResultListener,
                                      searchFilter: String,
                                      mode: DefaultGroupSearch): LDAPRequest = {
    new SearchRequest(
      listener,
      mode.searchGroupBaseDN.value.value,
      SearchScope.SUB,
      searchFilter,
      mode.groupNameAttribute.value
    )
  }

  private def searchUserGroupsLdapRequest(listener: AsyncSearchResultListener,
                                          user: LdapUser,
                                          mode: GroupsFromUserAttribute): LDAPRequest = {
    new SearchRequest(
      listener,
      user.dn.value.value,
      SearchScope.BASE,
      "(objectClass=*)",
      mode.groupsFromUserAttribute.value
    )
  }

  private def groupNameFromDn(dnString: String, mode: GroupsFromUserAttribute) = {
    val dn = new DN(dnString)
    if (dn.isDescendantOf(mode.searchGroupBaseDN.value.value, false)) {
      Try {
        dn.getRDN
          .getAttributes.toList
          .filter(_.getBaseName === mode.groupNameAttribute.value)
          .map(_.getValue)
          .headOption
      }.toOption.flatten
    } else {
      None
    }
  }

}

object UnboundidLdapAuthorizationService {
  def create(id: LdapService#Id,
             poolProvider: UnboundidLdapConnectionPoolProvider,
             connectionConfig: LdapConnectionConfig,
             userSearchFiler: UserSearchFilterConfig,
             userGroupsSearchFilter: UserGroupsSearchFilterConfig,
             blockingScheduler: Scheduler): Task[Either[ConnectionError, UnboundidLdapAuthorizationService]] = {
    implicit val blockingSchedulerImplicit: Scheduler = blockingScheduler
    (for {
      _ <- EitherT(UnboundidLdapConnectionPoolProvider.testBindingForAllHosts(connectionConfig))
        .recoverWith {
          case error: ConnectionError =>
            if (connectionConfig.ignoreLdapConnectivityProblems)
              EitherT.rightT(Unit)
            else
              EitherT.leftT(error)
        }
      connectionPool <- EitherT.liftF[Task, ConnectionError, LDAPConnectionPool](poolProvider.connect(connectionConfig))
    } yield new UnboundidLdapAuthorizationService(id, connectionPool, userGroupsSearchFilter, userSearchFiler, connectionConfig.requestTimeout)).value
  }
}

abstract class BaseUnboundidLdapService(connectionPool: LDAPConnectionPool,
                                        userSearchFiler: UserSearchFilterConfig,
                                        requestTimeout: FiniteDuration Refined Positive)
                                       (implicit blockingScheduler: Scheduler)
  extends LdapUserService with Logging {

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    connectionPool
      .process(searchUserLdapRequest(_, userSearchFiler, userId), requestTimeout)
      .flatMap {
        case Right(Nil) =>
          logger.debug("LDAP getting user CN returned no entries")
          Task.now(None)
        case Right(user :: Nil) =>
          Task(Some(LdapUser(userId, Dn(NonEmptyString.unsafeFrom(user.getDN)))))
        case Right(all@user :: _) =>
          logger.warn(s"LDAP search user - more than one user was returned: ${all.mkString(",")}. Picking first")
          Task(Some(LdapUser(userId, Dn(NonEmptyString.unsafeFrom(user.getDN)))))
        case Left(errorResult) =>
          logger.error(s"LDAP getting user CN returned error: [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.errorEx("LDAP getting user operation failed.", ex))
      }
  }

  private def searchUserLdapRequest(listener: AsyncSearchResultListener,
                                    userSearchFiler: UserSearchFilterConfig,
                                    userId: User.Id): LDAPRequest = {
    new SearchRequest(
      listener,
      userSearchFiler.searchUserBaseDN.value.value,
      SearchScope.SUB,
      s"${userSearchFiler.uidAttribute}=${Filter.encodeValue(userId.value.value)}"
    )
  }
}

final case class LdapUnexpectedResult(code: ResultCode, cause: String)
  extends Throwable(s"LDAP returned code: ${code.getName} [${code.intValue()}], cause: $cause")

final case class LdapConnectionConfig(connectionMethod: ConnectionMethod,
                                      poolSize: Int Refined Positive,
                                      connectionTimeout: FiniteDuration Refined Positive,
                                      requestTimeout: FiniteDuration Refined Positive,
                                      trustAllCerts: Boolean,
                                      bindRequestUser: BindRequestUser,
                                      ignoreLdapConnectivityProblems: Boolean)

object LdapConnectionConfig {

  val DEFAULT_CIRCUIT_BREAKER_CONFIG = CircuitBreakerConfig(Refined.unsafeApply(10), Refined.unsafeApply(10 seconds))

  final case class LdapHost private(url: UrlWithAuthority) {
    def isSecure: Boolean = url.schemeOption.contains(LdapHost.ldapsSchema)

    def host: String = url.host.value

    def port: Int = url.port.getOrElse(LdapHost.defaultPort)
  }
  object LdapHost {
    val ldapsSchema = "ldaps"
    val ldapSchema = "ldap"
    val defaultPort = 389

    def from(value: String): Option[LdapHost] = {
      Try(UrlWithAuthority.parse(value))
        .orElse(Try(UrlWithAuthority.parse(s"""//$value""")))
        .toOption
        .flatMap { url =>
          if (url.path.nonEmpty) None
          else if (!url.schemeOption.forall(Set(ldapSchema, ldapsSchema).contains)) None
          else Some(LdapHost(url))
        }
    }

    implicit val order: Order[LdapHost] = Order.by(_.toString())
  }

  sealed trait ConnectionMethod
  object ConnectionMethod {
    final case class SingleServer(host: LdapHost) extends ConnectionMethod
    final case class SeveralServers(hosts: NonEmptyList[LdapHost], haMethod: HaMethod) extends ConnectionMethod
    final case class ServerDiscovery(recordName: Option[String], providerUrl: Option[String], ttl: Option[FiniteDuration Refined Positive], useSSL: Boolean) extends ConnectionMethod
  }

  sealed trait HaMethod
  object HaMethod {
    case object RoundRobin extends HaMethod
    case object Failover extends HaMethod
  }

  sealed trait BindRequestUser
  object BindRequestUser {
    case object Anonymous extends BindRequestUser
    final case class CustomUser(dn: Dn, password: PlainTextSecret) extends BindRequestUser
  }

}

final case class UserSearchFilterConfig(searchUserBaseDN: Dn, uidAttribute: NonEmptyString)

final case class UserGroupsSearchFilterConfig(mode: UserGroupsSearchMode)
object UserGroupsSearchFilterConfig {

  sealed trait UserGroupsSearchMode
  object UserGroupsSearchMode {

    final case class DefaultGroupSearch(searchGroupBaseDN: Dn,
                                        groupNameAttribute: NonEmptyString,
                                        uniqueMemberAttribute: NonEmptyString,
                                        groupSearchFilter: NonEmptyString,
                                        groupAttributeIsDN: Boolean)
      extends UserGroupsSearchMode

    final case class GroupsFromUserAttribute(searchGroupBaseDN: Dn,
                                             groupNameAttribute: NonEmptyString,
                                             groupsFromUserAttribute: NonEmptyString)
      extends UserGroupsSearchMode

  }
}
