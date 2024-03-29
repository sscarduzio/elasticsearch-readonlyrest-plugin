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
import cats.effect.Resource
import cats.implicits._
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import io.lemonlabs.uri.UrlWithAuthority
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError.{HostConnectionError, ServerDiscoveryConnectionError}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, HaMethod, LdapHost}
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.HostnameResolver
import tech.beshu.ror.accesscontrol.domain.{Address, PlainTextSecret}
import tech.beshu.ror.accesscontrol.utils.ReleseablePool
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver
import tech.beshu.ror.utils.ScalaOps._

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

class UnboundidLdapConnectionPoolProvider {

  import UnboundidLdapConnectionPoolProvider._

  private val poolOfPools: ReleseablePool[Task, UnboundidLdapConnectionPool, LdapConnectionConfig] =
    new ReleseablePool(createConnectionPool)(pool => pool.close())

  def connect(connectionConfig: LdapConnectionConfig): Task[UnboundidLdapConnectionPool] =
    poolOfPools.get(connectionConfig).flatMap {
      case Left(ReleseablePool.ClosedPool) => Task.raiseError(ClosedLdapPool)
      case Right(pool) => Task.pure(pool)
    }

  def close(): Task[Unit] = poolOfPools.close

  private def createConnectionPool(connectionConfig: LdapConnectionConfig): Task[UnboundidLdapConnectionPool] = retry {
    Task
      .delay(createLdapConnectionPoolFrom(connectionConfig))
      .map(new UnboundidLdapConnectionPool(connectionConfig.poolName, _, connectionConfig.bindRequestUser))
//      .executeOn(ldapUnboundIdBlockingSchedulerImplicit)
//      .asyncBoundary
  }

  private def createLdapConnectionPoolFrom(connectionConfig: LdapConnectionConfig) = {
    val serverSet = createLdapServerSet(connectionConfig)
    val pool = new LDAPConnectionPool(
      serverSet,
      bindRequest(connectionConfig.bindRequestUser),
      if (connectionConfig.ignoreLdapConnectivityProblems) 0 else 1,
      connectionConfig.poolSize.value,
      null
    )
    pool.setConnectionPoolName(s"ROR-unboundid-connection-pool-${connectionConfig.poolName}")
    pool.setRetryFailedOperationsDueToInvalidConnections(true)
    pool.setCreateIfNecessary(true)
    pool
  }

}

object UnboundidLdapConnectionPoolProvider extends Logging {

  final case class LdapConnectionConfig(poolName: String,
                                        connectionMethod: ConnectionMethod,
                                        poolSize: Int Refined Positive,
                                        connectionTimeout: FiniteDuration Refined Positive,
                                        requestTimeout: FiniteDuration Refined Positive,
                                        trustAllCerts: Boolean,
                                        bindRequestUser: BindRequestUser,
                                        ignoreLdapConnectivityProblems: Boolean)

  object LdapConnectionConfig {

    val defaultCircuitBreakerConfig: CircuitBreakerConfig = CircuitBreakerConfig(
      maxFailures = 10,
      resetDuration = Refined.unsafeApply(10 seconds)
    )

    final case class LdapHost private(url: UrlWithAuthority) {
      def isSecure: Boolean = url.schemeOption.contains(LdapHost.ldapsSchema)

      def host: String = url.host.value

      def port: Int = url.port.getOrElse(LdapHost.defaultPort)
    }
    object LdapHost {
      private val ldapsSchema = "ldaps"
      private val ldapSchema = "ldap"
      private val defaultPort = 389

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
      final case class SingleServer(host: LdapHost)
        extends ConnectionMethod
      final case class SeveralServers(hosts: NonEmptyList[LdapHost],
                                      haMethod: HaMethod)
        extends ConnectionMethod
      final case class ServerDiscovery(recordName: Option[String],
                                       providerUrl: Option[String],
                                       ttl: Option[FiniteDuration Refined Positive],
                                       useSSL: Boolean)
        extends ConnectionMethod
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

  def testBindingForAllHosts(connectionConfig: LdapConnectionConfig): Task[Either[ConnectionError, Unit]] = {
    val ldapBindingTask = for {
      _ <- resolveHostnames(connectionConfig)
      result <- testLdapBinding(connectionConfig)
    } yield result

    ldapBindingTask
      .value
      .recover { case NonFatal(ex) =>
        logger.error("LDAP binding exception", ex)
        Left(toConnectionError(connectionConfig))
      }
//      .executeOn(ldapUnboundIdBlockingScheduler)
//      .asyncBoundary
  }

  private def resolveHostnames(connectionConfig: LdapConnectionConfig): EitherT[Task, ConnectionError, Unit] = {
    val ldapHosts = connectionConfig.connectionMethod match {
      case ConnectionMethod.SingleServer(host) => List(host)
      case ConnectionMethod.SeveralServers(hosts, _) => hosts.toList
      case _: ConnectionMethod.ServerDiscovery => List.empty
    }
    val hostnameResolver = new Ip4sBasedHostnameResolver()

    EitherT {
      ldapHosts
        .map { ldapHost =>
          resolveHostname(hostnameResolver, ldapHost)
            .map {
              case HostnameResolutionResult.Resolved => Right(())
              case HostnameResolutionResult.NotResolved => Left(ldapHost)
            }
        }
        .sequence
        .map(_.partitionMap(identity))
        .map {
          case (notResolvedLdapHosts, _) =>
            NonEmptyList.fromList(notResolvedLdapHosts)
              .map(HostConnectionError.apply)
              .toRight(())
              .swap
        }
    }
  }

  private def resolveHostname(hostnameResolver: HostnameResolver, ldapHost: LdapHost): Task[HostnameResolutionResult] = {
    Address
      .from(ldapHost.host)
      .map {
        case Address.Ip(_) =>
          Task.pure(HostnameResolutionResult.Resolved)
        case hostname: Address.Name =>
          hostnameResolver
            .resolve(hostname)
            .map {
              _.map((_: NonEmptyList[Address.Ip]) => HostnameResolutionResult.Resolved)
                .getOrElse(HostnameResolutionResult.NotResolved)
            }
      }
      .getOrElse(Task.pure(HostnameResolutionResult.Resolved)) // we cannot assume here that the LDAP host is invalid, so we pass it to the next validation step
  }

  private def testLdapBinding(connectionConfig: LdapConnectionConfig): EitherT[Task, ConnectionError, Unit] = {
    val maxRetries = if (connectionConfig.ignoreLdapConnectivityProblems) 0 else 2
    val connectionEstablishmentTimeout: FiniteDuration =
      connectionConfig.connectionTimeout.value.plus(3 seconds)
    val ldapConnection =
      Resource.make(
        getLdapConnection(connectionConfig).timeout(connectionEstablishmentTimeout)
      ) { conn =>
        Task(conn.close())
      }
    EitherT {
      retryBackoff(
        source = ldapConnection.use { connection =>
          testBinding(connection, connectionConfig)
        },
        maxRetries = maxRetries,
        firstDelay = 500 millis,
        backOffScaler = 1
      )
    }
  }

  private def getLdapConnection(connectionConfig: LdapConnectionConfig) = {
    val serverSet = createLdapServerSet(connectionConfig)
    Task(serverSet.getConnection)
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

  private def testBinding(connection: LDAPConnection, connectionConfig: LdapConnectionConfig): Task[Either[ConnectionError, Unit]] = {
    val bindReq = bindRequest(connectionConfig.bindRequestUser)
    Task(connection.bind(bindReq))
      .map { bindResult =>
        Either.cond(
          bindResult.getResultCode == ResultCode.SUCCESS,
          (),
          toConnectionError(connectionConfig)
        )
      }
  }

  private def bindRequest(bindRequestUser: BindRequestUser) = bindRequestUser match {
    case BindRequestUser.Anonymous => new SimpleBindRequest()
    case BindRequestUser.CustomUser(dn, password) => new SimpleBindRequest(dn.value.value, password.value.value)
  }

  private def toConnectionError(connectionConfig: LdapConnectionConfig): ConnectionError = {
    connectionConfig.connectionMethod match {
      case ConnectionMethod.SingleServer(host) => HostConnectionError(NonEmptyList.one(host))
      case ConnectionMethod.SeveralServers(hosts, _) => HostConnectionError(hosts)
      case ConnectionMethod.ServerDiscovery(recordName, providerUrl, _, _) => ServerDiscoveryConnectionError(recordName, providerUrl)
    }
  }

  sealed trait ConnectionError
  object ConnectionError {
    final case class HostConnectionError(hosts: NonEmptyList[LdapHost]) extends ConnectionError
    final case class ServerDiscoveryConnectionError(recordName: Option[String],
                                                    providerUrl: Option[String]) extends ConnectionError
  }
  case object ClosedLdapPool extends Exception

  private sealed trait HostnameResolutionResult
  private object HostnameResolutionResult {
    case object Resolved extends HostnameResolutionResult
    case object NotResolved extends HostnameResolutionResult
  }
}