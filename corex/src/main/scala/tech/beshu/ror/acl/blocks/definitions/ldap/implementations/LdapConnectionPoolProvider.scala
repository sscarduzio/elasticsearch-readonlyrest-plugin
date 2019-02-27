package tech.beshu.ror.acl.blocks.definitions.ldap.implementations

import cats.implicits._
import cats.data.NonEmptyList
import com.comcast.ip4s.{IpAddress, SocketAddress}
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import javax.net.ssl.SSLSocketFactory
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.ConnectionConfig.{BindRequestUser, ConnectionMethod, HaMethod, SslSettings}

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