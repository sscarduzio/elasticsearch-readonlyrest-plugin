package tech.beshu.ror.acl.blocks.definitions.ldap.implementations

import cats.implicits._
import cats.data.NonEmptyList
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig._
import tech.beshu.ror.acl.utils.ScalaOps.retry

import scala.util.control.NonFatal

object LdapConnectionPoolProvider extends Logging {

  final case class ConnectionError(hosts: NonEmptyList[LdapHost])

  def testBindingForAllHosts(connectionConfig: LdapConnectionConfig): Task[Either[ConnectionError, Unit]] = {
    val options = ldapOptions(connectionConfig)
    val serverSets = connectionConfig.connectionMethod match {
      case ConnectionMethod.SingleServer(ldap) if ldap.isSecure =>
        (ldap, new SingleServerSet(ldap.host, ldap.port, socketFactory(connectionConfig.trustAllCerts), options)) :: Nil
      case ConnectionMethod.SingleServer(ldap) =>
        (ldap, new SingleServerSet(ldap.host, ldap.port, options)) :: Nil
      case ConnectionMethod.SeveralServers(ldaps, _) if ldaps.head.isSecure =>
        ldaps
          .toSortedSet
          .map { ldap => (ldap, new SingleServerSet(ldap.host, ldap.port, socketFactory(connectionConfig.trustAllCerts), options)) }
          .toList
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
            retry(
              Task(server.getConnection)
                .bracket(
                  use = connection => Task(connection.bind(bindReq))
                )(
                  release = connection => Task(connection.close())
                )
            )
              .map { result => (host, result.getResultCode == ResultCode.SUCCESS) }
              .recover { case NonFatal(ex) =>
                logger.debug("LDAP binding exception", ex)
                (host, false)
              }
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
    val serverSet = ldapServerSet(connectionConfig.connectionMethod, ldapOptions(connectionConfig), connectionConfig.trustAllCerts)
    new LDAPConnectionPool(serverSet, bindRequest(connectionConfig.bindRequestUser), connectionConfig.poolSize.value)
  }

  private def ldapOptions(connectionConfig: LdapConnectionConfig) = {
    val options = new LDAPConnectionOptions()
    options.setConnectTimeoutMillis(connectionConfig.connectionTimeout.value.toMillis.toInt)
    options.setResponseTimeoutMillis(connectionConfig.requestTimeout.value.toMillis.toInt)
    options
  }

  private def ldapServerSet(connectionMethod: ConnectionMethod, options: LDAPConnectionOptions, trustAllCerts: Boolean) = {
    connectionMethod match {
      case ConnectionMethod.SingleServer(ldap) if ldap.isSecure =>
        new SingleServerSet(ldap.host, ldap.port, socketFactory(trustAllCerts), options)
      case ConnectionMethod.SingleServer(ldap) =>
        new SingleServerSet(ldap.host, ldap.port, options)
      case ConnectionMethod.SeveralServers(hosts, HaMethod.Failover) if hosts.head.isSecure =>
        new FailoverServerSet(
          hosts.toList.map(_.host).toArray[String],
          hosts.toList.map(_.port).toArray[Int],
          socketFactory(trustAllCerts),
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.Failover) =>
        new FailoverServerSet(
          hosts.toList.map(_.host).toArray[String],
          hosts.toList.map(_.port).toArray[Int],
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.RoundRobin) if hosts.head.isSecure =>
        new RoundRobinServerSet(
          hosts.toList.map(_.host).toArray[String],
          hosts.toList.map(_.port).toArray[Int],
          socketFactory(trustAllCerts),
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.RoundRobin) =>
        new RoundRobinServerSet(
          hosts.toList.map(_.host).toArray[String],
          hosts.toList.map(_.port).toArray[Int],
          options
        )
    }
  }

  private def socketFactory(trustAllCerts:Boolean) = {
    val sslUtil = if (trustAllCerts) new SSLUtil(new TrustAllTrustManager) else new SSLUtil()
    sslUtil.createSSLSocketFactory
  }

  private def bindRequest(bindRequestUser: BindRequestUser) = bindRequestUser match {
    case BindRequestUser.NoUser => new SimpleBindRequest()
    case BindRequestUser.CustomUser(dn, password) => new SimpleBindRequest(dn.value.value, password.value)
  }
}