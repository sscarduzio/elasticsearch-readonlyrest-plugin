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
package tech.beshu.ror.utils.containers

import java.io.InputStream

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{AddRequest, LDAPConnection, ResultCode}
import com.unboundid.ldif.LDIFReader
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import tech.beshu.ror.utils.containers.LdapContainer.defaults
import tech.beshu.ror.utils.misc.ScalaUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

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
    waitStrategy = Some(new LdapWaitStrategy(name, ldapInitScript))
  ) {

  def ldapPort: Int = this.mappedPort(defaults.ldap.port)

  def ldapSSLPort: Int = this.mappedPort(defaults.ldap.sslPort)

  def ldapHost: String = this.containerIpAddress

  def stop(): Unit = {
    this.container.stop()
  }
}

object LdapContainer {

  def create(name: String, ldapInitScript: String): LdapContainer = {
    val ldapContainer = new LdapContainer(name, ldapInitScript)
    ldapContainer.container.setNetwork(Network.SHARED)
    ldapContainer
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

private class LdapWaitStrategy(name: String,
                               ldapInitScript: String)
  extends AbstractWaitStrategy
    with LazyLogging {

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
    logger.info(s"LDAP container '$name' started")
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
           // fixme: if there is any connection problem during initilization, flatMap is ignored so there is no error message in log.
           // Retry with the same input stream doesn't help because all data has already been read by LDIFReader -
           // entries list is empty, initialization finishes with success but part of data from file still has not been added to ldap.
           // Test continues with corrupted ldap container state and then fails.
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
