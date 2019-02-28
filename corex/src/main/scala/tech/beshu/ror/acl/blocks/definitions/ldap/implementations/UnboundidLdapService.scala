package tech.beshu.ror.acl.blocks.definitions.ldap.implementations

import cats.implicits._
import cats.data.{EitherT, NonEmptySet}
import com.comcast.ip4s.{IpAddress, SocketAddress}
import com.unboundid.ldap.sdk._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.definitions.ldap._
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, SslSettings}
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionPoolProvider.ConnectionError
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{DefaultGroupSearch, GroupsFromUserAttribute}
import tech.beshu.ror.acl.domain.{Group, Secret, User}
import tech.beshu.ror.acl.utils.LdapConnectionPoolOps._

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class UnboundidLdapAuthenticationService(override val id: LdapService#Id,
                                         connectionPool: LDAPConnectionPool,
                                         userSearchFiler: UserSearchFilterConfig,
                                         requestTimeout: FiniteDuration Refined Positive)
  extends BaseUnboundidLdapService(connectionPool, userSearchFiler, requestTimeout)
    with LdapAuthenticationService {

  override def authenticate(user: User.Id, secret: Secret): Task[Boolean] = {
    ldapUserBy(user)
      .flatMap {
        case Some(ldapUser) =>
          ldapAuthenticate(ldapUser, secret)
        case None =>
          Task.now(false)
      }
  }

  private def ldapAuthenticate(user: LdapUser, password: Secret) = {
    Task(connectionPool.getConnection)
      .bracket(
        use = connection => Task(connection.bind(new SimpleBindRequest(user.dn.value.value, password.value)))
      )(
        release = connection => Task(connectionPool.releaseAndReAuthenticateConnection(connection))
      )
      .map(_.getResultCode == ResultCode.SUCCESS)
      .onError { case ex =>
        Task(logger.error("LDAP authenticate operation failed", ex))
      }
  }
}

object UnboundidLdapAuthenticationService {
  def create(id: LdapService#Id,
             connectionConfig: LdapConnectionConfig,
             userSearchFiler: UserSearchFilterConfig): Task[Either[ConnectionError, UnboundidLdapAuthenticationService]] = {
    (for {
      _ <- EitherT(LdapConnectionPoolProvider.testBindingForAllHosts(connectionConfig))
      connectionPool <- EitherT.liftF[Task, ConnectionError, LDAPConnectionPool](LdapConnectionPoolProvider.connect(connectionConfig))
    } yield new UnboundidLdapAuthenticationService(id, connectionPool, userSearchFiler, connectionConfig.requestTimeout)).value
  }
}

class UnboundidLdapAuthorizationService(override val id: LdapService#Id,
                                        connectionPool: LDAPConnectionPool,
                                        groupsSearchFilter: UserGroupsSearchFilterConfig,
                                        userSearchFiler: UserSearchFilterConfig,
                                        requestTimeout: FiniteDuration Refined Positive)
  extends BaseUnboundidLdapService(connectionPool, userSearchFiler, requestTimeout)
    with LdapAuthorizationService {

  override def groupsOf(id: User.Id): Task[Set[Group]] = {
    ldapUserBy(id)
      .flatMap {
        case Some(user) =>
          groupsSearchFilter.mode match {
            case defaultSearchGroupMode: DefaultGroupSearch => groupsFrom(defaultSearchGroupMode, user)
            case groupsFromUserAttribute: GroupsFromUserAttribute => groupsFrom(groupsFromUserAttribute, user)
          }
        case None =>
          Task.now(Set.empty)
      }
  }

  private def groupsFrom(defaultSearchGroupMode: DefaultGroupSearch, user: LdapUser): Task[Set[Group]] = {
    val searchFilter = searchFilterFrom(defaultSearchGroupMode, user)
    logger.debug(s"LDAP search string: $searchFilter | groupNameAttr: ${defaultSearchGroupMode.groupNameAttribute}")
    connectionPool
      .process(searchGroupsLdapRequest(_, searchFilter, defaultSearchGroupMode), requestTimeout)
      .flatMap {
        case Right(results) =>
          Task {
            results
              .flatMap { r =>
                Option(r.getAttributeValue(defaultSearchGroupMode.groupNameAttribute.value))
                  .flatMap(NonEmptyString.unapply)
              }
              .map(Group.apply)
              .toSet
          }
        case Left(errorResult) =>
          logger.debug(s"LDAP getting user groups returned error code: [${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.debug(s"LDAP getting user groups returned error", ex))
      }
  }

  private def groupsFrom(mode: GroupsFromUserAttribute, user: LdapUser): Task[Set[Group]] = {
    logger.debug(s"LDAP search string: ${user.dn.value.value} | groupsFromUserAttribute: ${mode.groupsFromUserAttribute.value}")
    connectionPool
      .process(searchUserGroupsLdapRequest(_, user, mode), requestTimeout)
      .flatMap {
        case Right(results) =>
          Task {
            results
              .flatMap { r =>
                Option(r.getAttributeValues(mode.groupsFromUserAttribute.value))
                  .toList.flatMap(_.toList)
                  .flatMap(groupNameFromDn(_, mode))
                  .flatMap(NonEmptyString.unapply)
              }
              .map(Group.apply)
              .toSet
          }
        case Left(errorResult) =>
          logger.debug(s"LDAP getting user groups returned error code: [${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.debug(s"LDAP getting user groups returned error", ex))
      }
  }

  private def searchFilterFrom(mode: DefaultGroupSearch, user: LdapUser) = {
    s"(&${mode.groupSearchFilter}(${mode.uniqueMemberAttribute}=${Filter.encodeValue(user.dn.value.value)}))"
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
            connectionConfig: LdapConnectionConfig,
            userSearchFiler: UserSearchFilterConfig,
            userGroupsSearchFilter: UserGroupsSearchFilterConfig): Task[Either[ConnectionError, UnboundidLdapAuthorizationService]] = {
   (for {
     _ <- EitherT(LdapConnectionPoolProvider.testBindingForAllHosts(connectionConfig))
     connectionPool <- EitherT.liftF[Task, ConnectionError, LDAPConnectionPool](LdapConnectionPoolProvider.connect(connectionConfig))
   } yield new UnboundidLdapAuthorizationService(id, connectionPool, userGroupsSearchFilter, userSearchFiler, connectionConfig.requestTimeout)).value
 }
}

abstract class BaseUnboundidLdapService(connectionPool: LDAPConnectionPool,
                                        userSearchFiler: UserSearchFilterConfig,
                                        requestTimeout: FiniteDuration Refined Positive)
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
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.error("LDAP getting user operation failed.", ex))
      }
  }

  private def searchUserLdapRequest(listener: AsyncSearchResultListener,
                                    userSearchFiler: UserSearchFilterConfig,
                                    userId: User.Id): LDAPRequest = {
    new SearchRequest(
      listener,
      userSearchFiler.searchUserBaseDN.value.value,
      SearchScope.SUB,
      s"${userSearchFiler.uidAttribute}=${Filter.encodeValue(userId.value)}"
    )
  }
}

final case class LdapUnexpectedResult(code: ResultCode, cause: String) extends Throwable

final case class LdapConnectionConfig(connectionMethod: ConnectionMethod,
                                      poolSize: Int Refined Positive,
                                      connectionTimeout: FiniteDuration Refined Positive,
                                      requestTimeout: FiniteDuration Refined Positive,
                                      ssl: Option[SslSettings],
                                      bindRequestUser: BindRequestUser)
object LdapConnectionConfig {

  sealed trait ConnectionMethod
  object ConnectionMethod {
    final case class SingleServer(host: SocketAddress[IpAddress]) extends ConnectionMethod
    final case class SeveralServers(hosts: NonEmptySet[SocketAddress[IpAddress]], haMethod: HaMethod) extends ConnectionMethod
  }

  sealed trait HaMethod
  object HaMethod {
    case object RoundRobin extends HaMethod
    case object Failover extends HaMethod
  }

  // todo: if ssl is enabled, all severs should be have ldaps schema
  final case class SslSettings(trustAllCerts: Boolean)

  sealed trait BindRequestUser
  object BindRequestUser {
    case object NoUser extends BindRequestUser
    final case class CustomUser(dn: Dn, password: Secret) extends BindRequestUser
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
                                        groupSearchFilter: NonEmptyString)
      extends UserGroupsSearchMode
    final case class GroupsFromUserAttribute(searchGroupBaseDN: Dn,
                                             groupNameAttribute: NonEmptyString,
                                             groupsFromUserAttribute: NonEmptyString)
      extends UserGroupsSearchMode
  }
}
