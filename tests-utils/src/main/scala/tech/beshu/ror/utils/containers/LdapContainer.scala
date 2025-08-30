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

import better.files.Dispose.FlatMap.Implicits
import better.files.{Disposable, Dispose, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{AddRequest, LDAPConnection, LDAPException, ResultCode}
import com.unboundid.ldif.LDIFReader
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import tech.beshu.ror.utils.containers.LdapSingleContainer.{InitScriptSource, defaults}
import tech.beshu.ror.utils.containers.LdapWaitStrategy.*
import tech.beshu.ror.utils.misc.ScalaUtils.*

import java.io.{BufferedReader, InputStreamReader}
import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}

class LdapContainer private[containers](name: String, ldapInitScript: InitScriptSource)
  extends GenericContainer(
    dockerImage = "osixia/openldap:1.5.0",
    env = Map(
      "LDAP_ORGANISATION" -> defaults.ldap.organisation,
      "LDAP_DOMAIN" -> defaults.ldap.domain,
      "LDAP_ADMIN_PASSWORD" -> defaults.ldap.adminPassword,
      "LDAP_TLS_VERIFY_CLIENT" -> "try"
    ),
    exposedPorts = Seq(LdapContainer.port, LdapContainer.sslPort),
    waitStrategy = Some(new LdapWaitStrategy(name, ldapInitScript))
  ) with LdapSingleContainer {

  def originalPort: Int = LdapContainer.port

  def ldapPort: Int = this.mappedPort(LdapContainer.port)

  def ldapSSLPort: Int = this.mappedPort(LdapContainer.sslPort)

  def ldapHost: String = this.containerIpAddress

  override def stop(): Unit = {
    this.container.stop()
  }
}

object LdapContainer {

  val port = 389
  val sslPort = 636

  def create(name: String, ldapInitScript: InitScriptSource): LdapSingleContainer = {
    val ldapContainer = new LdapContainer(name, ldapInitScript)
    ldapContainer.container.setNetwork(Network.SHARED)
    ldapContainer
  }

  def create(name: String, ldapInitScript: String): LdapSingleContainer = {
    create(name, InitScriptSource.fromString(ldapInitScript))
  }

}

class NonStoppableLdapContainer(name: String, ldapInitScript: InitScriptSource)
  extends LdapContainer(name, ldapInitScript) {

  override def start(): Unit = ()

  override def stop(): Unit = ()

  private[NonStoppableLdapContainer] def privateStart(): Unit = super.start()
}

object NonStoppableLdapContainer {
  def createAndStart(name: String, ldapInitScript: InitScriptSource): LdapSingleContainer = {
    val ldap = new NonStoppableLdapContainer(name, ldapInitScript)
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
      initLdapFromFile(connection)
    }
  }

  private def initLdapFromFile(connection: LDAPConnection) = {
    Task
      .sequence {
        readEntries().map { entry =>
          Task(connection.add(new AddRequest(entry.toLDIF: _*)))
            .flatMap {
              case result if Set(ResultCode.SUCCESS, ResultCode.ENTRY_ALREADY_EXISTS).contains(result.getResultCode) =>
                Task.now(())
              case result =>
                Task.raiseError(new IllegalStateException(s"Adding entry failed, due to: ${result.getResultCode}"))
            }
            .onErrorRecover {
              case ex: LDAPException if ex.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
                Task.now(())
            }
        }
      }
      .map(_ => ())
  }

  private def readEntries() = {
    val result = for {
      inputStream <- ldapInitScript match {
        case InitScriptSource.Resource(resourceName) =>
          new Dispose(new BufferedReader(new InputStreamReader(Resource.getAsStream(resourceName))))
        case InitScriptSource.AFile(file) =>
          file.bufferedReader
      }
      reader <- new Dispose(new LDIFReader(inputStream))
    } yield {
      Iterator
        .continually(Option(reader.readEntry()))
        .takeWhile(_.isDefined)
        .flatten
        .toList
    }
    result.get()
  }

  private def runOnBindedLdapConnection(action: LDAPConnection => Task[Unit]): Task[Unit] = {
    defaults.ldap.bindDn match {
      case Some(bindDn) =>
        Task(new LDAPConnection(waitStrategyTarget.getHost, waitStrategyTarget.getMappedPort(LdapContainer.port)))
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

  private implicit val ldifReaderDisposable: Disposable[LDIFReader] = Disposable(_.close())
}

object LdapWaitStrategy {
  private val containerStartupTimeout: FiniteDuration = 5 minutes
}
