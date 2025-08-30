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
package tech.beshu.ror.utils.containers.windows

import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.lifecycle.Startable
import org.testcontainers.shaded.org.bouncycastle.cert.*
import tech.beshu.ror.utils.containers.LdapSingleContainer
import tech.beshu.ror.utils.containers.LdapSingleContainer.InitScriptSource
import tech.beshu.ror.utils.containers.windows.WindowsPseudoSingleContainerLdap.WindowsPseudoGenericContainerLdap

import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}

class WindowsPseudoSingleContainerLdap(service: InMemoryLdapService)
  extends SingleContainer[GenericContainer[_]] with LdapSingleContainer {

  override val container: GenericContainer[_] =
    new WindowsPseudoGenericContainerLdap(service)

  def originalPort: Int = service.ldapPort

  def ldapPort: Int = service.ldapPort

  def ldapSSLPort: Int = service.ldapSSLPort

  def ldapHost: String = service.ldapHost
}

object WindowsPseudoSingleContainerLdap {

  def create(name: String, ldapInitScript: InitScriptSource): LdapSingleContainer = {
    new WindowsPseudoSingleContainerLdap(new InMemoryLdapService(name, ldapInitScript))
  }

  def create(name: String, ldapInitScript: String): LdapSingleContainer = {
    create(name, InitScriptSource.fromString(ldapInitScript))
  }

  object defaults {
    val connectionTimeout: FiniteDuration = 5 seconds
    val containerStartupTimeout: FiniteDuration = 5 minutes

    object ldap {
      val domain = "example.com"
      val domainDn = domain.split("\\.").map(dc => s"dc=$dc").mkString(",")
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

  private class WindowsPseudoGenericContainerLdap(startable: Startable)
    extends GenericContainer[WindowsPseudoGenericContainerLdap]("noop:latest") {

    override def start(): Unit = {
      doStart()
    }

    override def doStart(): Unit = {
      startable.start()
    }

    override def stop(): Unit = {
      startable.stop()
      super.stop()
    }

    override def getContainerId: String = "WindowsPseudoGenericContainerLdap"

    override def getDockerImageName: String = "WindowsPseudoGenericContainerLdap"
  }

}