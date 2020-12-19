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

import cats.data.NonEmptyList
import cats.effect.Resource
import cats.implicits._
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.LdapConnectionConfig._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError.{HostConnectionError, ServerDiscoveryConnectionError}
import tech.beshu.ror.utils.ScalaOps.retry

import scala.util.control.NonFatal

class UnboundidLdapConnectionPoolProvider {

  import UnboundidLdapConnectionPoolProvider._

  private val poolOfPools: ReleseablePool[Task, LDAPConnectionPool, LdapConnectionConfig] = new ReleseablePool(createConnection)(pool => Task(pool.close()))

  def connect(connectionConfig: LdapConnectionConfig): Task[LDAPConnectionPool] =
    poolOfPools.get(connectionConfig).flatMap {
      case Left(ReleseablePool.ClosedPool) => Task.raiseError(ClosedLdapPool)
      case Right(connection) => Task.pure(connection)
    }

  def close(): Task[Unit] = poolOfPools.close

  private def createConnection(connectionConfig: LdapConnectionConfig): Task[LDAPConnectionPool] = retry {
    Task {
      val serverSet = createLdapServerSet(connectionConfig)
      val pool = new LDAPConnectionPool(
        serverSet,
        bindRequest(connectionConfig.bindRequestUser),
        connectionConfig.poolSize.value
      )
      pool.setMaxConnectionAgeMillis(60000)
      pool
    }
  }
}

object UnboundidLdapConnectionPoolProvider extends Logging {
  def testBindingForAllHosts(connectionConfig: LdapConnectionConfig): Task[Either[ConnectionError, Unit]] = {
    val serverSet = createLdapServerSet(connectionConfig)
    val bindReq = bindRequest(connectionConfig.bindRequestUser)
    val bindResult = retry {
      val resource = Resource.make(Task(serverSet.getConnection)) { conn =>
        Task(conn.close())
      }
      resource.use { connection =>
        Task(connection.bind(bindReq))
      }
    }
    bindResult
      .map(_.getResultCode == ResultCode.SUCCESS)
      .recover { case NonFatal(ex) =>
        logger.error("LDAP binding exception", ex)
        false
      }
      .map {
        case true => Right(())
        case false => Left(
          connectionConfig.connectionMethod match {
            case ConnectionMethod.SingleServer(host) => HostConnectionError(NonEmptyList.one(host))
            case ConnectionMethod.SeveralServers(hosts, _) => HostConnectionError(hosts)
            case ConnectionMethod.ServerDiscovery(recordName, providerUrl, _, _) => ServerDiscoveryConnectionError(recordName, providerUrl)
          }
        )
      }
  }

  private def bindRequest(bindRequestUser: BindRequestUser) = bindRequestUser match {
    case BindRequestUser.Anonymous => new SimpleBindRequest()
    case BindRequestUser.CustomUser(dn, password) => new SimpleBindRequest(dn.value.value, password.value.value)
  }

  private def createLdapServerSet(connectionConfig: LdapConnectionConfig) = {
    val options = ldapOptions(connectionConfig)
    ldapServerSet(connectionConfig.connectionMethod, options, connectionConfig.trustAllCerts)
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
        new SingleServerSet(ldap.host, ldap.port, sslSocketFactory(trustAllCerts), options)
      case ConnectionMethod.SingleServer(ldap) =>
        new SingleServerSet(ldap.host, ldap.port, options)
      case ConnectionMethod.SeveralServers(hosts, HaMethod.Failover) if hosts.head.isSecure =>
        new FailoverServerSet(
          hosts.toList.map(_.host).toArray[String],
          hosts.toList.map(_.port).toArray[Int],
          sslSocketFactory(trustAllCerts),
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
          sslSocketFactory(trustAllCerts),
          options
        )
      case ConnectionMethod.SeveralServers(hosts, HaMethod.RoundRobin) =>
        new RoundRobinServerSet(
          hosts.toList.map(_.host).toArray[String],
          hosts.toList.map(_.port).toArray[Int],
          options
        )
      case ConnectionMethod.ServerDiscovery(recordName, providerUrl, ttl, useSSL) =>
        new DNSSRVRecordServerSet(
          recordName.orNull,
          providerUrl.orNull,
          ttl.map(_.value.toSeconds).getOrElse(0),
          if (useSSL) sslSocketFactory(trustAllCerts) else null,
          options
        )
    }
  }

  private def sslSocketFactory(trustAllCerts: Boolean) = {
    val sslUtil = if (trustAllCerts) new SSLUtil(new TrustAllTrustManager) else new SSLUtil()
    sslUtil.createSSLSocketFactory
  }

  sealed trait ConnectionError
  object ConnectionError {

    final case class HostConnectionError(hosts: NonEmptyList[LdapHost]) extends ConnectionError
    final case class ServerDiscoveryConnectionError(recordName: Option[String], providerUrl: Option[String]) extends ConnectionError

  }
  case object ClosedLdapPool extends Exception
}