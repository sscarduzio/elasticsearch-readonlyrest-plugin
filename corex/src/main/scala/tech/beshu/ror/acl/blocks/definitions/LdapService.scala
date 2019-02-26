package tech.beshu.ror.acl.blocks.definitions

import cats.data.{EitherT, NonEmptyList, NonEmptySet}
import cats.implicits._
import cats.{Eq, Show}
import com.comcast.ip4s.{IpAddress, SocketAddress}
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import javax.net.ssl.SSLSocketFactory
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.definitions.ConnectionConfig.{BindRequestUser, ConnectionMethod, HaMethod, SslSettings}
import tech.beshu.ror.acl.blocks.definitions.LdapAuthenticationService.Credentials
import tech.beshu.ror.acl.blocks.definitions.LdapConnectionPoolProvider.ConnectionError
import tech.beshu.ror.acl.blocks.definitions.LdapService.Name
import tech.beshu.ror.acl.blocks.definitions.LdapUnexpectedResult.{NoEntriesReturned, UnexpectedResultCode}
import tech.beshu.ror.acl.blocks.definitions.UserGroupsSearchFilterConfig.UserGroupsSearchMode
import tech.beshu.ror.acl.blocks.definitions.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{DefaultGroupSearch, GroupsFromUserAttribute}
import tech.beshu.ror.acl.domain.{Group, Secret, User}
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.acl.utils.LdapConnectionPoolOps._

import scala.concurrent.duration.FiniteDuration

sealed trait LdapService extends Item {
  override type Id = Name
  def id: Id

  override implicit def show: Show[Name] = Name.nameShow
}

object LdapService {
  final case class Name(value: NonEmptyString)
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value.value)
  }
}

trait LdapAuthService extends LdapService with LdapAuthenticationService with LdapAuthorizationService

class ComposedLdapAuthService(override val id: LdapService#Id,
                              ldapAuthenticationService: LdapAuthenticationService,
                              ldapAuthorizationService: LdapAuthorizationService)
  extends LdapAuthService {

  // todo: optimize (calling ldapUser twice)

  override def authenticate(credentials: Credentials): Task[Boolean] = ldapAuthenticationService.authenticate(credentials)

  override def groupsOf(id: User.Id): Task[Set[Group]] = ldapAuthorizationService.groupsOf(id)

}

trait LdapAuthenticationService extends LdapService {
  def authenticate(credentials: Credentials): Task[Boolean]
}
object LdapAuthenticationService {
  final case class Credentials(userName: User.Id, secret: Secret)
}

class UnboundidLdapAuthenticationService(override val id: LdapService#Id,
                                         connectionPool: LDAPConnectionPool,
                                         userSearchFiler: UserSearchFilterConfig,
                                         requestTimeout: FiniteDuration Refined Positive)
  extends LdapAuthenticationService
  with Logging {

  override def authenticate(credentials: Credentials): Task[Boolean] = {
    ldapUserBy(credentials.userName)
      .flatMap(ldapAuthenticate(_, credentials.secret))
  }

  private def ldapUserBy(userId: User.Id) = {
    connectionPool
      .process(searchUserLdapRequest(_, userId), requestTimeout)
      .flatMap {
        case Right(Nil) =>
          logger.debug("LDAP getting user CN returned no entries")
          Task.raiseError(NoEntriesReturned)
        case Right(user :: Nil) =>
          Task(LdapUser(userId, Dn(NonEmptyString.unsafeFrom(user.getDN))))
        case Right(all@user :: _) =>
          logger.warn(s"LDAP search user - more than one user was returned: ${all.mkString(",")}. Picking first")
          Task(LdapUser(userId, Dn(NonEmptyString.unsafeFrom(user.getDN))))
        case Left(errorResult) =>
          Task.raiseError(UnexpectedResultCode(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.error("LDAP getting user operation failed.", ex))
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

  private def searchUserLdapRequest(listener: AsyncSearchResultListener, userId: User.Id): LDAPRequest = {
    new SearchRequest(
      listener,
      userSearchFiler.searchUserBaseDN.value.value,
      SearchScope.SUB,
      s"${userSearchFiler.uidAttribute}=${Filter.encodeValue(userId.value)}"
    )
  }
}

object UnboundidLdapAuthenticationService {
  def create(id: LdapService#Id,
             connectionConfig: ConnectionConfig,
             userSearchFiler: UserSearchFilterConfig): Task[Either[ConnectionError, UnboundidLdapAuthenticationService]] = {
    (for {
      _ <- EitherT(LdapConnectionPoolProvider.testBindingForAllHosts(connectionConfig))
      connectionPool <- EitherT.liftF[Task, ConnectionError, LDAPConnectionPool](LdapConnectionPoolProvider.connect(connectionConfig))
    } yield new UnboundidLdapAuthenticationService(id, connectionPool, userSearchFiler, connectionConfig.requestTimeout)).value
  }
}

final case class LdapUser(id: User.Id, dn: Dn)

sealed trait LdapUnexpectedResult extends Throwable
object LdapUnexpectedResult {
  final case class UnexpectedResultCode(code: ResultCode, cause: String) extends LdapUnexpectedResult
  case object NoEntriesReturned extends LdapUnexpectedResult
}

trait LdapAuthorizationService extends LdapService {
  def groupsOf(id: User.Id): Task[Set[Group]]
}

class UnboundidLdapAuthorizationService(override val id: LdapService#Id,
                                        connectionPool: LDAPConnectionPool,
                                        groupsSearchFilter: UserGroupsSearchFilterConfig,
                                        requestTimeout: FiniteDuration Refined Positive)
  extends LdapAuthorizationService
  with Logging {

  override def groupsOf(id: User.Id): Task[Set[Group]] = {
    val ldapUser: LdapUser = ???
    groupsSearchFilter.mode match {
      case defaultSearchGroupMode: DefaultGroupSearch => groupsFrom(defaultSearchGroupMode, ldapUser)
      case groupsFromUserAttribute: GroupsFromUserAttribute => groupsFrom(groupsFromUserAttribute, ldapUser)
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
                Option(r.getAttributeValue(defaultSearchGroupMode.groupNameAttribute))
                  .flatMap(NonEmptyString.unapply)
              }
              .map(Group.apply)
              .toSet
          }
        case Left(errorResult) =>
          Task.raiseError(UnexpectedResultCode(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.debug(s"LDAP getting user groups returned error", ex))
      }
  }

  private def groupsFrom(groupsFromUserAttribute: GroupsFromUserAttribute, user: LdapUser): Task[Set[Group]] = ???

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
      mode.groupNameAttribute
    )
  }
}

final case class ConnectionConfig(connectionMethod: ConnectionMethod,
                                  poolSize: Int Refined Positive,
                                  connectionTimeout: FiniteDuration Refined Positive,
                                  requestTimeout: FiniteDuration Refined Positive,
                                  ssl: Option[SslSettings],
                                  bindRequestUser: BindRequestUser)
object ConnectionConfig {

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

final case class Dn(value: NonEmptyString)

final case class UserSearchFilterConfig(searchUserBaseDN: Dn, uidAttribute: NonEmptyString)

final case class UserGroupsSearchFilterConfig(mode: UserGroupsSearchMode)
object UserGroupsSearchFilterConfig {

  sealed trait UserGroupsSearchMode
  object UserGroupsSearchMode {
    final case class DefaultGroupSearch(searchGroupBaseDN: Dn,
                                        uniqueMemberAttribute: String,
                                        groupSearchFilter: String,
                                        groupNameAttribute: String)
      extends UserGroupsSearchMode
    final case class GroupsFromUserAttribute(attribute: NonEmptyString)
      extends UserGroupsSearchMode
  }
}

object LdapConnectionPoolProvider {

  final case class ConnectionError(hosts: NonEmptyList[SocketAddress[IpAddress]])

  def testBindingForAllHosts(connectionConfig: ConnectionConfig): Task[Either[ConnectionError, Unit]] = {
    val options = ldapOptions(connectionConfig)
    val serverSets = connectionConfig.connectionMethod match {
      case ConnectionMethod.SingleServer(host) =>
        (host, new SingleServerSet(host.ip.toUriString, host.port.value, options)) :: Nil
      case ConnectionMethod.SeveralServers(hosts, _) =>
        hosts
          .toSortedSet
          .map { host => (host, new SingleServerSet(host.ip.toUriString, host.port.value, options)) }
          .toList
    }
    val bindReq = bindRequest(connectionConfig.bindRequestUser)
    serverSets
      .map { case (host, server) =>
        Task(server.getConnection)
          .bracket(
            use = connection => Task(connection.bind(bindReq))
          )(
            release = connection => Task(connection.close())
          )
          .map { result => (host, result.getResultCode == ResultCode.SUCCESS) }
      }
      .sequence
      .map(_.collect { case (host, false) => host} )
      .map(NonEmptyList.fromList)
      .map {
        case None => Right(())
        case Some(hostsWithNoConnection) => Left(ConnectionError(hostsWithNoConnection))
      }
  }

  def connect(connectionConfig: ConnectionConfig): Task[LDAPConnectionPool] = Task {
    val serverSet = connectionConfig.ssl match {
      case Some(sslSettings) => ldapServerSet(connectionConfig.connectionMethod, ldapOptions(connectionConfig), socketFactory(sslSettings))
      case None => ldapServerSet(connectionConfig.connectionMethod, ldapOptions(connectionConfig))
    }
    new LDAPConnectionPool(serverSet, bindRequest(connectionConfig.bindRequestUser), connectionConfig.poolSize.value)
  }

  private def ldapOptions(connectionConfig: ConnectionConfig) = {
    val options = new LDAPConnectionOptions()
    options.setConnectTimeoutMillis(connectionConfig.connectionTimeout.value.toMillis.toInt)
    options.setResponseTimeoutMillis(connectionConfig.requestTimeout.value.toMillis.toInt)
    options
  }

  private def ldapServerSet(connectionMethod: ConnectionMethod, options: LDAPConnectionOptions, ssl: SSLSocketFactory) = {
    connectionMethod match {
      case ConnectionMethod.SingleServer(host) =>
        new SingleServerSet(host.ip.toUriString, host.port.value, ssl, options)
      case ConnectionMethod.SeveralServers(hosts, HaMethod.Failover) =>
        new FailoverServerSet(
          hosts.toSortedSet.map(_.ip).map(_.toUriString).toArray[String],
          hosts.toSortedSet.map(_.port.value).toArray[Int],
          ssl,
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.RoundRobin) =>
        new RoundRobinServerSet(
          hosts.toSortedSet.map(_.ip).map(_.toUriString).toArray[String],
          hosts.toSortedSet.map(_.port.value).toArray[Int],
          ssl,
          options
        )
    }
  }

  private def ldapServerSet(connectionMethod: ConnectionMethod, options: LDAPConnectionOptions) = {
    connectionMethod match {
      case ConnectionMethod.SingleServer(host) =>
        new SingleServerSet(host.ip.toUriString, host.port.value, options)
      case ConnectionMethod.SeveralServers(hosts, HaMethod.Failover) =>
        new FailoverServerSet(
          hosts.toSortedSet.map(_.ip).map(_.toUriString).toArray[String],
          hosts.toSortedSet.map(_.port.value).toArray[Int],
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.RoundRobin) =>
        new RoundRobinServerSet(
          hosts.toSortedSet.map(_.ip).map(_.toUriString).toArray[String],
          hosts.toSortedSet.map(_.port.value).toArray[Int],
          options
        )
    }
  }

  private def socketFactory(sslSettings: SslSettings) = {
    val sslUtil = if (sslSettings.trustAllCerts) new SSLUtil(new TrustAllTrustManager) else new SSLUtil()
    sslUtil.createSSLSocketFactory
  }

  private def bindRequest(bindRequestUser: BindRequestUser) = bindRequestUser match {
    case BindRequestUser.NoUser => new SimpleBindRequest()
    case BindRequestUser.CustomUser(dn, password) => new SimpleBindRequest(dn.value.value, password.value)
  }
}