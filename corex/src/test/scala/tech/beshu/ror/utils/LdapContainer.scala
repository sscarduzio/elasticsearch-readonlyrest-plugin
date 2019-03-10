package tech.beshu.ror.utils

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.scalalogging.StrictLogging
import com.unboundid.ldap.sdk.{AddRequest, LDAPConnection, ResultCode}
import com.unboundid.ldif.LDIFReader
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import tech.beshu.ror.acl.utils.ScalaOps.retryBackoff
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
      "LDAP_ADMIN_PASSWORD" -> defaults.ldap.adminPassword,
      "LDAP_TLS_VERIFY_CLIENT" -> "try"
    ),
    exposedPorts = Seq(defaults.ldap.port),
    waitStrategy = Some(ldapWaitStrategy(name, ldapInitScript))
  ) {

  def ldapPort: Int = this.mappedPort(defaults.ldap.port)

  def ldapSSLPort: Int = this.mappedPort(defaults.ldap.sslPort)

  def ldapHost: String = this.containerIpAddress

  def stop() = {
    this.container.stop()
  }
}

object LdapContainer extends StrictLogging {

  private def ldapWaitStrategy(name: String,
                               ldapInitScript: String) = new AbstractWaitStrategy {
    override def waitUntilReady(): Unit = {
      logger.info(s"Waiting for LDAP container '$name' ...")
      Task(getClass.getResourceAsStream(ldapInitScript))
        .bracket(stream =>
          retryBackoff(ldapInitiate(stream), 15, 1 second, 1)
        )(stream =>
          Task(stream.close())
        )
        .onErrorHandle { ex =>
          logger.error("LDAP container startup failed", ex)
          throw ex
        }
        .runSyncUnsafe(defaults.containerStartupTimeout)
      logger.info(s"LDAP container '$name' stated")
    }

    private def ldapInitiate(ldapInitScriptInputStream: InputStream) = {
      runOnBindedLdapConnection { connection =>
        initLdapFromFile(connection, ldapInitScriptInputStream)
      }
    }

    private def initLdapFromFile(connection: LDAPConnection, scriptInputStream: InputStream) = {
      val reader = new LDIFReader(scriptInputStream)
      val entries = Iterator
        .continually(Option(reader.readEntry()))
        .takeWhile(_.isDefined)
        .flatten
        .toList
      Task
        .sequence {
          entries.map { entry =>
            Task(connection.add(new AddRequest(entry.toLDIF: _*)))
              .flatMap {
                case result if result.getResultCode == ResultCode.SUCCESS =>
                  Task.now(())
                case result =>
                  Task.raiseError(new IllegalStateException(s"Adding entry failed, due to: ${result.getResultCode}"))
              }
          }
        }
        .map(_ => ())
    }

    private def runOnBindedLdapConnection(action: LDAPConnection => Task[Unit]): Task[Unit] = {
      defaults.ldap.bindDn match {
        case Some(bindDn) =>
          Task(new LDAPConnection(waitStrategyTarget.getContainerIpAddress, waitStrategyTarget.getMappedPort(defaults.ldap.port)))
            .bracket(connection =>
              Task(connection.bind(bindDn, defaults.ldap.adminPassword))
                .flatMap {
                  case result if result.getResultCode == ResultCode.SUCCESS => action(connection)
                  case result => Task.raiseError(new IllegalStateException(s"LDAP '$name' bind problem - error ${result.getResultCode.intValue()}"))
                }
            )(connection =>
              Task(connection.close())
            )
        case None =>
          Task.raiseError(new IllegalStateException(s"Cannot create bind DN from LDAP config data"))
      }
    }
  }

  object defaults {
    val connectionTimeout: FiniteDuration = 5 seconds
    val containerStartupTimeout: FiniteDuration = 5 minutes

    object ldap {
      val port = 389
      val sslPort = 636
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
