package tech.beshu.ror.utils

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.scalalogging.StrictLogging
import com.unboundid.ldap.sdk.{AddRequest, LDAPConnection, LDAPConnectionOptions, ResultCode}
import com.unboundid.ldif.LDIFReader
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import tech.beshu.ror.acl.utils.ScalaOps
import tech.beshu.ror.utils.LdapContainer.{defaults, ldapWaitStrategy}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.tools.nsc.interpreter.InputStream

class LdapContainer(name: String, ldapInitScript: String)
  extends GenericContainer(
    dockerImage = "osixia/openldap:1.1.7",
    env = Map(
      "LDAP_ORGANISATION" -> defaults.ldap.organisation,
      "LDAP_DOMAIN" -> defaults.ldap.domain,
      "LDAP_ADMIN_PASSWORD" -> defaults.ldap.adminPassword
    ),
    exposedPorts = Seq(defaults.ldap.port),
    waitStrategy = Some(ldapWaitStrategy(name, ldapInitScript))
  ) {

  def ldapPort: Int = this.mappedPort(defaults.ldap.port)
  def ldapHost: String = this.containerIpAddress
}

object LdapContainer extends StrictLogging {

  private def ldapWaitStrategy(name: String, ldapInitScript: String) = new AbstractWaitStrategy {
    override def waitUntilReady(): Unit = {
      logger.info(s"Waiting for LDAP container '$name' ...")
      Task(getClass.getResourceAsStream(ldapInitScript))
        .bracket(stream =>
          ScalaOps
            .retryBackoff(ldapInitiate(stream), 15, 1 second)
        )(stream =>
          Task(stream.close())
        )
        .runSyncUnsafe(defaults.containerStartupTimeout)
      logger.info(s"LDAP container '$name' stated")
    }


    private def ldapInitiate(ldapInitScriptInputStream: InputStream) = {
      runOnLdapConnection { connection =>
        defaults.ldap.bindDn match {
          case Some(bindDn) =>
            Task(connection.bind(bindDn, defaults.ldap.adminPassword))
              .flatMap {
                case r if r.getResultCode == ResultCode.SUCCESS =>
                  initLdapFromFile(connection, ldapInitScriptInputStream)
                case r =>
                  Task.raiseError(new IllegalArgumentException(s"LDAP '$name' bind problem - error ${r.getResultCode.intValue()}"))
              }
              .map(_ => ())
          case None =>
            Task.raiseError(new IllegalArgumentException("Cannot create bind DN from LDAP config data"))
        }
      }.onErrorHandle(ex => logger.info(s"LDAP $name initiation failed. ${ex.getMessage}"))
    }

    private def initLdapFromFile(connection: LDAPConnection, scriptInputStream: InputStream) = Task {
      val reader = new LDIFReader(scriptInputStream)
      Iterator
        .continually(Option(reader.readEntry()))
        .takeWhile(_.isDefined)
        .flatten
        .map(entry => connection.add(new AddRequest(entry.toLDIF: _*)))
    }

    private def runOnLdapConnection(action: LDAPConnection => Task[Unit]): Task[Unit] = {
      Task(createConnection)
        .bracket(connection =>
          action(connection)
        )(connection =>
          Task(connection.close())
        )
    }

    private def createConnection: LDAPConnection = {
      val options = new LDAPConnectionOptions()
      options.setConnectTimeoutMillis(defaults.connectionTimeout.toMillis.toInt)
      new LDAPConnection(options)
    }

  }

  object defaults {
    val connectionTimeout: FiniteDuration = 5 seconds
    val containerStartupTimeout: FiniteDuration = 5 minutes

    object ldap {
      val port = 389
      val domain = "example.com"
      val organisation = "example"
      val adminName = "admin"
      val adminPassword = "password"
      val bindDn: Option[String] = {
        Option(
          defaults.ldap.domain
            .split("\\.").toList
            .map(part => s"dc=$part")
            .mkString(","))
          .filter(_.trim.nonEmpty)
          .map(dc => s"cn=${defaults.ldap.adminName},$dc")
      }
    }

  }

}
