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

import better.files.{Disposable, Dispose, File, Resource, disposeFlatMap}
import com.dimafeng.testcontainers.SingleContainer
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPException, ResultCode}
import com.unboundid.ldif.LDIFReader
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.GenericContainer as JavaGenericContainer
import tech.beshu.ror.utils.containers.LdapSingleContainer.InitScriptSource
import tech.beshu.ror.utils.containers.LdapWaitStrategy.*
import tech.beshu.ror.utils.containers.windows.{NonStoppableInMemoryLdapService, WindowsPseudoSingleContainerLdap}
import tech.beshu.ror.utils.misc.OsUtils
import tech.beshu.ror.utils.misc.ScalaUtils.*

import java.io.{BufferedReader, InputStreamReader}
import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}

trait LdapSingleContainer extends SingleContainer[JavaGenericContainer[_]] {

  def originalPort: Int

  def ldapPort: Int

  def ldapSSLPort: Int

  def ldapHost: String

  def doStart(): Unit = start()
}

object LdapSingleContainer {

  sealed trait InitScriptSource

  object InitScriptSource {
    final case class Resource(name: String) extends InitScriptSource

    final case class AFile(file: File) extends InitScriptSource

    implicit def fromString(name: String): InitScriptSource = Resource(name)

    implicit def fromFile(file: File): InitScriptSource = AFile(file)
  }

  def create(name: String, ldapInitScript: InitScriptSource): LdapSingleContainer = {
    if (OsUtils.isWindows) {
      WindowsPseudoSingleContainerLdap.create(name, ldapInitScript)
    } else {
      LdapContainer.create(name, ldapInitScript)
    }
  }

  def create(name: String, ldapInitScript: String): LdapSingleContainer = {
    create(name, InitScriptSource.fromString(ldapInitScript))
  }

  object defaults {
    val connectionTimeout: FiniteDuration = 5 seconds
    val containerStartupTimeout: FiniteDuration = 5 minutes

    object ldap {
      val domain = "example.com"
      val domainDn: String = domain.split("\\.").map(dc => s"dc=$dc").mkString(",")
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

  def initLdap(connection: LDAPConnection, ldapInitScript: InitScriptSource): Unit = {
    val entries = readEntries(ldapInitScript)

    entries.foreach { entry =>
      try {
        val result = connection.add(entry) // or AddRequest(entry.toLDIF: _*) if needed
        if (result.getResultCode != ResultCode.SUCCESS &&
          result.getResultCode != ResultCode.ENTRY_ALREADY_EXISTS) {
          throw new IllegalStateException(s"Failed to add entry: ${result.getResultCode}")
        }
      } catch {
        case ex: LDAPException if ex.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        // Ignore, already exists
        case ex: Exception =>
          // Re-throw other exceptions
          throw ex
      }
    }
  }

  private def readEntries(ldapInitScript: InitScriptSource) = {
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


  private implicit val ldifReaderDisposable: Disposable[LDIFReader] = Disposable(_.close())
}

object NonStoppableLdapSingleContainer {
  def createAndStart(name: String, ldapInitScript: InitScriptSource): LdapSingleContainer = {
    if (OsUtils.isWindows) {
      NonStoppableInMemoryLdapService.createAndStart(name, ldapInitScript)
    } else {
      NonStoppableLdapContainer.createAndStart(name, ldapInitScript)
    }
  }
}
