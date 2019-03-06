package tech.beshu.ror.acl.blocks.definitions.ldap.implementations

import cats.implicits._
import cats.data.NonEmptyList
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import javax.net.ssl.SSLSocketFactory
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig._
import tech.beshu.ror.acl.utils.ScalaOps.retry

import scala.util.control.NonFatal

object LdapConnectionPoolProvider {

  final case class ConnectionError(hosts: NonEmptyList[LdapHost])

  def testBindingForAllHosts(connectionConfig: LdapConnectionConfig): Task[Either[ConnectionError, Unit]] = {
    val options = ldapOptions(connectionConfig)
    val serverSets = connectionConfig.connectionMethod match {
      case ConnectionMethod.SingleServer(ldap) =>
        (ldap, new SingleServerSet(ldap.host, ldap.port, options)) :: Nil
      case ConnectionMethod.SeveralServers(ldaps, _) =>
        ldaps
          .toSortedSet
          .map { ldap => (ldap, new SingleServerSet(ldap.host, ldap.port, options)) }
          .toList
    }
    val bindReq = bindRequest(connectionConfig.bindRequestUser)
    Task
      .sequence {
        serverSets
          .map { case (host, server) =>
            retry {
              Task {
                val connection = server.getConnection
                try {
                  //connection.connect(host.host, host.port) // todo: 
                  connection.bind(bindReq)
                } finally {
                  connection.close()
                }
              }
            }
              .map { result => (host, result.getResultCode == ResultCode.SUCCESS) }
              .recover { case NonFatal(_) => (host, false) }
          }
      }
      .map(_.collect { case (host, false) => host })
      .map(NonEmptyList.fromList)
      .map {
        case None => Right(())
        case Some(hostsWithNoConnection) => Left(ConnectionError(hostsWithNoConnection))
      }
  }

  def connect(connectionConfig: LdapConnectionConfig): Task[LDAPConnectionPool] = Task {
    val serverSet = connectionConfig.ssl match {
      case Some(sslSettings) => ldapServerSet(connectionConfig.connectionMethod, ldapOptions(connectionConfig), socketFactory(sslSettings))
      case None => ldapServerSet(connectionConfig.connectionMethod, ldapOptions(connectionConfig))
    }
    new LDAPConnectionPool(serverSet, bindRequest(connectionConfig.bindRequestUser), connectionConfig.poolSize.value)
  }

  private def ldapOptions(connectionConfig: LdapConnectionConfig) = {
    val options = new LDAPConnectionOptions()
    options.setConnectTimeoutMillis(connectionConfig.connectionTimeout.value.toMillis.toInt)
    options.setResponseTimeoutMillis(connectionConfig.requestTimeout.value.toMillis.toInt)
    options
  }

  private def ldapServerSet(connectionMethod: ConnectionMethod, options: LDAPConnectionOptions, ssl: SSLSocketFactory) = {
    connectionMethod match {
      case ConnectionMethod.SingleServer(ldap) =>
        new SingleServerSet(ldap.host, ldap.port, ssl, options)
      case ConnectionMethod.SeveralServers(hosts, HaMethod.Failover) =>
        new FailoverServerSet(
          hosts.toSortedSet.map(_.host).toArray[String],
          hosts.toSortedSet.map(_.port).toArray[Int],
          ssl,
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.RoundRobin) =>
        new RoundRobinServerSet(
          hosts.toSortedSet.map(_.host).toArray[String],
          hosts.toSortedSet.map(_.port).toArray[Int],
          ssl,
          options
        )
    }
  }

  private def ldapServerSet(connectionMethod: ConnectionMethod, options: LDAPConnectionOptions) = {
    connectionMethod match {
      case ConnectionMethod.SingleServer(ldap) =>
        new SingleServerSet(ldap.host, ldap.port, options)
      case ConnectionMethod.SeveralServers(hosts, HaMethod.Failover) =>
        new FailoverServerSet(
          hosts.toSortedSet.map(_.host).toArray[String],
          hosts.toSortedSet.map(_.port).toArray[Int],
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.RoundRobin) =>
        new RoundRobinServerSet(
          hosts.toSortedSet.map(_.host).toArray[String],
          hosts.toSortedSet.map(_.port).toArray[Int],
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