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

import better.files.Dispose
import better.files.Dispose.FlatMap.Implicits
import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{LDAPConnection, ResultCode}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import tech.beshu.ror.utils.containers.LdapContainer.{InitScriptSource, defaults, initLdap}
import tech.beshu.ror.utils.containers.LdapWaitStrategy.*
import tech.beshu.ror.utils.misc.ScalaUtils.*

import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}

class OpenLdapContainer private[containers](name: String, ldapInitScript: InitScriptSource)
  extends GenericContainer(
    dockerImage = "osixia/openldap:1.5.0",
    env = Map(
      "LDAP_ORGANISATION" -> defaults.ldap.organisation,
      "LDAP_DOMAIN" -> defaults.ldap.domain,
      "LDAP_ADMIN_PASSWORD" -> defaults.ldap.adminPassword,
      "LDAP_TLS_VERIFY_CLIENT" -> "try"
    ),
    exposedPorts = Seq(OpenLdapContainer.port, OpenLdapContainer.sslPort),
    waitStrategy = Some(new LdapWaitStrategy(name, ldapInitScript))
  ) with LdapContainer {

  def originalPort: Int = OpenLdapContainer.port

  def ldapPort: Int = this.mappedPort(OpenLdapContainer.port)

  def ldapSSLPort: Int = this.mappedPort(OpenLdapContainer.sslPort)

  def ldapHost: String = this.containerIpAddress

  override def stop(): Unit = {
    this.container.stop()
  }
}

object OpenLdapContainer {

  val port = 389
  val sslPort = 636

  def create(name: String, ldapInitScript: InitScriptSource): LdapContainer = {
    val ldapContainer = new OpenLdapContainer(name, ldapInitScript)
    ldapContainer.container.setNetwork(Network.SHARED)
    ldapContainer
  }

  def create(name: String, ldapInitScript: String): LdapContainer = {
    create(name, InitScriptSource.fromString(ldapInitScript))
  }

}

class NonStoppableOpenLdapContainer(name: String, ldapInitScript: InitScriptSource)
  extends OpenLdapContainer(name, ldapInitScript) {

  override def start(): Unit = ()

  override def stop(): Unit = ()

  private[NonStoppableOpenLdapContainer] def privateStart(): Unit = super.start()
}

object NonStoppableOpenLdapContainer {
  def createAndStart(name: String, ldapInitScript: InitScriptSource): LdapContainer = {
    val ldap = new NonStoppableOpenLdapContainer(name, ldapInitScript)
    ldap.container.setNetwork(Network.SHARED)
    ldap.privateStart()
    ldap
  }
}

private class LdapWaitStrategy(name: String,
                               ldapInitScript: InitScriptSource)
  extends HostPortWaitStrategy()
    with LazyLogging
    with Implicits {

  override def waitUntilReady(): Unit = {
    super.waitUntilReady()
    logger.info(s"Waiting for LDAP container '$name' ...")
    retryBackoff(ldapInitiate(), 15, 1 second, 1)
      .onErrorHandle { ex =>
        logger.error("LDAP container startup failed", ex)
        throw ex
      }
      .runSyncUnsafe(containerStartupTimeout)
    logger.info(s"LDAP container '$name' started")
  }

  private def ldapInitiate() = {
    runOnBindedLdapConnection { connection =>
      initLdap(connection, ldapInitScript)
    }
  }

  private def runOnBindedLdapConnection(action: LDAPConnection => Unit): Task[Unit] = {
    defaults.ldap.bindDn match {
      case Some(bindDn) =>
        Task(new LDAPConnection(waitStrategyTarget.getHost, waitStrategyTarget.getMappedPort(OpenLdapContainer.port)))
          .bracket(connection =>
            Task(connection.bind(bindDn, defaults.ldap.adminPassword))
              .flatMap {
                case result if result.getResultCode == ResultCode.SUCCESS => Task.delay(action(connection))
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

object LdapWaitStrategy {
  private val containerStartupTimeout: FiniteDuration = 5 minutes
}
