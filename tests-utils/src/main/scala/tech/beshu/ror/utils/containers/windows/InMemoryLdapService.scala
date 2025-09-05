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

import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig, InMemoryListenerConfig}
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import org.testcontainers.lifecycle.Startable
import org.testcontainers.shaded.org.bouncycastle.cert.*
import org.testcontainers.shaded.org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.testcontainers.shaded.org.bouncycastle.jce.provider.BouncyCastleProvider
import org.testcontainers.shaded.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import tech.beshu.ror.utils.containers.LdapSingleContainer
import tech.beshu.ror.utils.containers.LdapSingleContainer.{InitScriptSource, defaults, initLdap}

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.{KeyPairGenerator, KeyStore, Security}
import java.util.Date
import javax.net.ssl.{KeyManager, KeyManagerFactory}
import javax.security.auth.x500.X500Principal
import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}
import scala.util.Using

class InMemoryLdapService(name: String, ldapInitScript: InitScriptSource)
  extends Startable with LazyLogging {

  private val config: InMemoryDirectoryServerConfig = {
    val c = new InMemoryDirectoryServerConfig(defaults.ldap.domainDn)
    c.setSchema(null)
    c.addAdditionalBindCredentials(defaults.ldap.bindDn.get, defaults.ldap.adminPassword)
    c.setListenerConfigs(
      InMemoryListenerConfig.createLDAPConfig("ldap", null, 0, null, false, false),
      createLDAPSListener("ldaps", 0)
    )
    c
  }

  private val server = new InMemoryDirectoryServer(config)

  def start(): Unit = {
    server.startListening()
    logger.info(s"LDAP in-memory server '$name' started on port ${server.getListenPort}")
    Using(new LDAPConnection(ldapHost, ldapPort, defaults.ldap.bindDn.get, defaults.ldap.adminPassword)) { connection =>
      initLdap(connection, ldapInitScript)
    }
  }

  def stop(): Unit = {
    server.shutDown(true)
    logger.info(s"LDAP in-memory server '$name' stopped")
  }

  def ldapPort: Int = server.getListenPort("ldap")

  def ldapSSLPort: Int = server.getListenPort("ldaps")

  def ldapHost: String = "localhost"

  private def createLDAPSListener(name: String, port: Int): InMemoryListenerConfig = {
    val keyManager = KeyManagerUtil.createSelfSignedKeyManager()
    val sslUtil = new SSLUtil(keyManager, new TrustAllTrustManager)
    val serverSocketFactory = sslUtil.createSSLServerSocketFactory()
    InMemoryListenerConfig.createLDAPSConfig(name, null, port, serverSocketFactory, null)
  }

  private object KeyManagerUtil {

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
      keyStore.setKeyEntry("alias", keyPair.getPrivate, "pass".toCharArray, Array(cert))

      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(keyStore, "pass".toCharArray)
      kmf.getKeyManagers.head
    }
  }
}

private class NonStoppableInMemoryLdapService private(name: String, ldapInitScript: InitScriptSource)
  extends InMemoryLdapService(name, ldapInitScript) {

  override def start(): Unit = ()

  override def stop(): Unit = ()

  private[NonStoppableInMemoryLdapService] def privateStart(): Unit = super.start()
}

object NonStoppableInMemoryLdapService {
  def createAndStart(name: String, ldapInitScript: InitScriptSource): LdapSingleContainer = {
    val ldap = new NonStoppableInMemoryLdapService(name, ldapInitScript)
    ldap.privateStart()
    new WindowsPseudoSingleContainerLdap(ldap)
  }
}
