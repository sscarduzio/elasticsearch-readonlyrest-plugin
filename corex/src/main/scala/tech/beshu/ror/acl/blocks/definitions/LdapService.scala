package tech.beshu.ror.acl.blocks.definitions

import cats.implicits._
import cats.data.NonEmptySet
import cats.effect.IO
import cats.{Eq, MonadError, Show}
import com.comcast.ip4s.{IpAddress, SocketAddress}
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import javax.net.ssl.SSLSocketFactory
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ConnectionConfig.{BindRequestUser, ConnectionMethod, HaMethod, SslSettings}
import tech.beshu.ror.acl.blocks.definitions.LdapAuthenticationService.Credentials
import tech.beshu.ror.acl.blocks.definitions.LdapService.Name
import tech.beshu.ror.acl.domain.{Group, Secret, User}
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

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
                                         connectionConfig: ConnectionConfig,
                                         userSearchFiler: UserSearchFilterConfig)
  extends LdapAuthenticationService {

  override def authenticate(credentials: Credentials): Task[Boolean] = ???
}

trait LdapAuthorizationService extends LdapService {
  def groupsOf(id: User.Id): Task[Set[Group]]
}

class UnboundidLdapAuthorizationService(override val id: LdapService#Id,
                                        connectionConfig: ConnectionConfig)
  extends LdapAuthorizationService {

  override def groupsOf(id: User.Id): Task[Set[Group]] = ???
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

trait LdapConnectionPoolProvider {

  def testBindingForAllHosts(connectionConfig: ConnectionConfig): Task[List[(SocketAddress[IpAddress], Boolean)]] = {
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
    Task.fromIO {
      serverSets
        .map { case (host, server) =>
          IO(server.getConnection)
            .bracket(connection =>
              IO(connection.bind(bindReq))
                .map { result => (host, result.getResultCode == ResultCode.SUCCESS) }
            )(connection =>
              IO(connection.close())
            )
        }
        .sequence
    }
  }

  def connect(connectionConfig: ConnectionConfig): LDAPConnectionPool = {
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