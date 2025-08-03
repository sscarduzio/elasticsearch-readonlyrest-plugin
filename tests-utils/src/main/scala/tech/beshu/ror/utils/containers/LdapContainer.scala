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

import better.files.{File, Resource}
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig, InMemoryListenerConfig}
import com.unboundid.ldap.sdk.{LDAPConnection, ResultCode}
import com.unboundid.ldif.LDIFReader
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.utils.containers.LdapContainer.{InitScriptSource, defaults}
import tech.beshu.ror.utils.misc.ScalaUtils.*

import java.io.{BufferedReader, InputStreamReader}
import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}
import com.dimafeng.testcontainers.SingleContainer
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.lifecycle.Startable
import org.testcontainers.shaded.org.bouncycastle.cert.*
import org.testcontainers.shaded.org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.testcontainers.shaded.org.bouncycastle.jce.provider.BouncyCastleProvider
import org.testcontainers.shaded.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import sun.security.x509.*

import java.math.BigInteger
import java.security.{KeyPairGenerator, KeyStore, Security}
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.{KeyManager, KeyManagerFactory}
import javax.security.auth.x500.X500Principal


class LdapGenericContainer(startable: Startable)
  extends GenericContainer[LdapGenericContainer]("noop:latest") {

  // Override start to delegate to the service
  override def start(): Unit = {
    doStart()
  }

  // Override doStart to delegate to the service
  override def doStart(): Unit = {
    startable.start()
  }

  // Delegate stop to the service
  override def stop(): Unit = {
    startable.stop()
    super.stop() // no-op, but safe to call
  }

  // Optional override to prevent real Docker interaction
  override def getContainerId: String = "fake-container"
}

class LdapContainer(service: InMemoryLdapService)
  extends SingleContainer[GenericContainer[_]] {

  override val container: GenericContainer[_] =
    new LdapGenericContainer(service)

  def ldapPort: Int = service.ldapPort

  def ldapSSLPort: Int = service.ldapSSLPort

  def ldapHost: String = service.ldapHost

}

class InMemoryLdapService(name: String, ldapInitScript: InitScriptSource)
  extends Startable with LazyLogging {

  private val config = new InMemoryDirectoryServerConfig(defaults.ldap.domainDn)
  config.setSchema(null)
  config.addAdditionalBindCredentials(defaults.ldap.bindDn.get, defaults.ldap.adminPassword)

  config.setListenerConfigs(
    InMemoryListenerConfig.createLDAPConfig("ldap", null, 0, null, false, false),
    createLDAPSListener("ldaps", 0)
  )

  private val server = new InMemoryDirectoryServer(config)

  def start(): Unit = {
    server.startListening()
    logger.info(s"LDAP in-memory server '$name' started on port ${server.getListenPort}")
    initLdapFromFile()

  }

  def stop(): Unit = {
    server.shutDown(true)
    logger.info(s"LDAP in-memory server '$name' stopped")
  }

  def ldapPort: Int = server.getListenPort("ldap")

  def ldapSSLPort: Int = server.getListenPort("ldaps")

  def ldapHost: String = "localhost"

  private def initLdapFromFile(): Unit = {
    val entries = readEntries()
    val connection = new LDAPConnection(ldapHost, ldapPort, defaults.ldap.bindDn.get, defaults.ldap.adminPassword)
    entries.foreach { entry =>
      try {
        val result = connection.add(entry)
        if (result.getResultCode != ResultCode.SUCCESS && result.getResultCode != ResultCode.ENTRY_ALREADY_EXISTS) {
          throw new IllegalStateException(s"Failed to add entry: ${result.getResultCode}")
        }
      } catch {
        case e: Exception =>
          if (!e.getMessage.contains("ENTRY_ALREADY_EXISTS")) {
            throw e
          }
      }
    }
    connection.close()
  }

  private def readEntries() = {
    val reader = ldapInitScript match {
      case InitScriptSource.Resource(resourceName) =>
        new BufferedReader(new InputStreamReader(Resource.getAsStream(resourceName)))
      case InitScriptSource.AFile(file) =>
        file.newBufferedReader
    }
    val ldifReader = new LDIFReader(reader)
    Iterator
      .continually(Option(ldifReader.readEntry()))
      .takeWhile(_.isDefined)
      .flatten
      .toList
  }

  def createLDAPSListener(name: String, port: Int): InMemoryListenerConfig = {
    val keyManager = KeyManagerUtil.createSelfSignedKeyManager()
    val sslUtil = new SSLUtil(keyManager, new TrustAllTrustManager)
    val serverSocketFactory = sslUtil.createSSLServerSocketFactory()
    InMemoryListenerConfig.createLDAPSConfig(name, null, port, serverSocketFactory, null)
  }

  object KeyManagerUtil {

    Security.addProvider(new BouncyCastleProvider())

    def createSelfSignedKeyManager(dn: String = "CN=localhost"): KeyManager = {
      val keyPairGen = KeyPairGenerator.getInstance("RSA")
      keyPairGen.initialize(2048)
      val keyPair = keyPairGen.generateKeyPair()

      val now = new Date()
      val notAfter = new Date(now.getTime + 365L * 24 * 60 * 60 * 1000) // 1 year
      val certBuilder: X509v3CertificateBuilder = new JcaX509v3CertificateBuilder(
        new X500Principal(dn),
        BigInteger.valueOf(System.currentTimeMillis()),
        now,
        notAfter,
        new X500Principal(dn),
        keyPair.getPublic
      )

      val signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)

      val certHolder = certBuilder.build(signer)
      val cert: X509Certificate = new JcaX509CertificateConverter()
        .setProvider("BC")
        .getCertificate(certHolder)

      val keyStore = KeyStore.getInstance("JKS")
      keyStore.load(null, null)
      keyStore.setKeyEntry("alias", keyPair.getPrivate, "changeit".toCharArray, Array(cert))

      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(keyStore, "changeit".toCharArray)
      kmf.getKeyManagers.head
    }
  }
}

object LdapContainer {

  sealed trait InitScriptSource

  object InitScriptSource {
    final case class Resource(name: String) extends InitScriptSource

    final case class AFile(file: File) extends InitScriptSource

    implicit def fromString(name: String): InitScriptSource = Resource(name)

    implicit def fromFile(file: File): InitScriptSource = AFile(file)
  }

  def create(name: String, ldapInitScript: InitScriptSource): LdapContainer = {
    new LdapContainer(new InMemoryLdapService(name, ldapInitScript))
  }

  def create(name: String, ldapInitScript: String): LdapContainer = {
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
}

class NonStoppableLdapContainer private(name: String, ldapInitScript: InitScriptSource)
  extends InMemoryLdapService(name, ldapInitScript) {

  override def start(): Unit = ()

  override def stop(): Unit = ()

  private[NonStoppableLdapContainer] def privateStart(): Unit = super.start()
}

object NonStoppableLdapContainer {
  def createAndStart(name: String, ldapInitScript: InitScriptSource): LdapContainer = {
    val ldap = new NonStoppableLdapContainer(name, ldapInitScript)
    ldap.privateStart()
    new LdapContainer(ldap)
  }
}
