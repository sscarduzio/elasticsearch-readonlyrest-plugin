package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import cats.data.NonEmptyList
import cats.effect.Resource
import cats.implicits._
import com.unboundid.ldap.sdk.{LDAPConnectionPool, _}
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.LdapConnectionConfig._
import tech.beshu.ror.utils.ScalaOps.retry

import scala.util.control.NonFatal
final class UnboundidLdapConnectionPoolProvider extends LdapConnectionPoolProvider{

  import UnboundidLdapConnectionPoolProvider._

  private val poolOfPools: ReleseablePool[Task, LDAPConnectionPool, LdapConnectionConfig] = new ReleseablePool(createConnection)(pool => Task(pool.close()))

  override def connect(connectionConfig: LdapConnectionConfig): Task[LDAPConnectionPool] =
    poolOfPools.get(connectionConfig).flatMap {
      case Left(value) => Task.raiseError(new Exception(value.toString))
      case Right(value) => Task.pure(value)
    }

  private def createConnection(connectionConfig: LdapConnectionConfig): Task[LDAPConnectionPool] = retry {
    Task {
      val serverSet = createLdapServerSet(connectionConfig)
      new LDAPConnectionPool(serverSet, bindRequest(connectionConfig.bindRequestUser), connectionConfig.poolSize.value)
    }
  }
  override def close(): Task[Unit] = poolOfPools.close
}

object UnboundidLdapConnectionPoolProvider extends Logging {
  final case class ConnectionError(hosts: NonEmptyList[LdapHost])

  def testBindingForAllHosts(connectionConfig: LdapConnectionConfig): Task[Either[ConnectionError, Unit]] = {
    val server = createLdapServerSet(connectionConfig)
    val bindReq = bindRequest(connectionConfig.bindRequestUser)
    val bindResult = retry {
      val resource = Resource.make(Task(server.getConnection)) { conn =>
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
        case false => Left(ConnectionError {
          connectionConfig.connectionMethod match {
            case ConnectionMethod.SingleServer(host) => NonEmptyList.one(host)
            case ConnectionMethod.SeveralServers(hosts, _) => hosts
          }
        })
      }
  }

  private def socketFactory(trustAllCerts: Boolean) = {
    val sslUtil = if (trustAllCerts) new SSLUtil(new TrustAllTrustManager) else new SSLUtil()
    sslUtil.createSSLSocketFactory
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

}