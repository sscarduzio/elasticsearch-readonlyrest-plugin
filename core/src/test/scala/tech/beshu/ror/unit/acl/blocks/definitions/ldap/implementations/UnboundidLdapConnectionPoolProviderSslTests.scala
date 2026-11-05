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
package tech.beshu.ror.unit.acl.blocks.definitions.ldap.implementations

import better.files.File
import cats.data.NonEmptyList
import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig, InMemoryListenerConfig}
import com.unboundid.ldap.sdk.{ResultCode, SearchRequest, SearchResult, SearchResultEntry, SearchScope}
import com.unboundid.util.ssl.cert.ManageCertificates
import com.unboundid.util.ssl.{KeyStoreKeyManager, SSLUtil, TrustAllTrustManager}
import eu.timepit.refined.api.Refined
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError.CannotConnectError
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.{
  BindRequestUser,
  ConnectionMethod,
  HaMethod,
  LdapHost
}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.{
  UnboundidLdapConnectionPool,
  UnboundidLdapConnectionPoolProvider
}
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.TestsUtils.unsafeNes
import tech.beshu.ror.utils.containers.DnsServerContainer
import tech.beshu.ror.utils.misc.OsUtils.ignoreOnWindows

import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.Certificate
import scala.concurrent.duration.*
import scala.language.postfixOps

class UnboundidLdapConnectionPoolProviderSslTests extends AnyWordSpec with BeforeAndAfterAll with Inside {

  "An UnboundidLdapConnectionPoolProvider" should {
    "not connect to an LDAPS server" when {
      "the certificate does not contain the host name" in {
        inside(connect(config(singleServer(otherNameServerPort)))) { case Left(CannotConnectError(_, _, cause)) =>
          cause should include("Hostname verification failed")
        }
      }
      "the certificates of all failover servers do not contain the host name" in {
        val method = severalServers(HaMethod.Failover, otherNameServerPort, secondOtherNameServerPort)
        inside(connect(config(method))) { case Left(CannotConnectError(_, _, cause)) =>
          cause should include("Hostname verification failed")
        }
      }
      "the certificates of all round robin servers do not contain the host name" in {
        val method = severalServers(HaMethod.RoundRobin, otherNameServerPort, secondOtherNameServerPort)
        inside(connect(config(method))) { case Left(CannotConnectError(_, _, cause)) =>
          cause should include("Hostname verification failed")
        }
      }
      "the certificate of the server from the SRV record does not contain the SRV target name" in {
        ignoreOnWindows {
          val dnsContainer = new DnsServerContainer("localhost", otherNameServerPort)
          dnsContainer.start()
          try {
            val method = ConnectionMethod.ServerDiscovery(
              recordName = None,
              providerUrl = Some(s"dns://${dnsContainer.dnsHost}:${dnsContainer.dnsPort}"),
              ttl = None,
              useSSL = true
            )
            inside(connect(config(method))) { case Left(CannotConnectError(_, _, cause)) =>
              cause should include("Hostname verification failed")
            }
          } finally dnsContainer.stop()
        }
      }
      "the certificate is not in the trust store" in {
        inside(connect(config(singleServer(untrustedServerPort)))) { case Left(CannotConnectError(_, _, cause)) =>
          cause should not include "Hostname verification failed"
        }
      }
    }
    "connect to an LDAPS server" when {
      "the certificate contains the host name" in {
        assertConnectsAndSearches(config(singleServer(localhostServerPort)))
      }
      "the certificate does not contain the host name, but the host name verification is disabled" in {
        assertConnectsAndSearches(config(singleServer(otherNameServerPort)).copy(sslHostnameVerification = false))
      }
      "the certificate does not contain the host name, but all certificates are trusted" in {
        assertConnectsAndSearches(
          config(singleServer(otherNameServerPort)).copy(trustAllCerts = true, sslHostnameVerification = false)
        )
      }
    }
    "create a pool which cannot search" when {
      "the certificate does not contain the host name and the LDAP connectivity problems are ignored" in {
        val connectionConfig = config(singleServer(otherNameServerPort)).copy(ignoreLdapConnectivityProblems = true)
        withConnection(connectionConfig) { result =>
          inside(result) { case Right(pool) =>
            searchError(search(pool)) should include("Hostname verification failed")
          }
        }
      }
    }
  }

  private val baseDn = "dc=example,dc=com"
  private val password = "changeit"
  private val certificatesDir = File.newTemporaryDirectory("ror-ldaps-tests-")

  private val trustStoreProperties = List(
    "javax.net.ssl.trustStore",
    "javax.net.ssl.trustStorePassword",
    "javax.net.ssl.trustStoreType"
  )

  private var originalTrustStoreProperties: List[(String, Option[String])] = List.empty

  private lazy val server = {
    val localhostKeyStore = generateKeyStore(name = "localhost", subject = "localhost")
    val otherNameKeyStore = generateKeyStore(name = "other.example", subject = "other.example")
    val untrustedKeyStore = generateKeyStore(name = "untrusted-localhost", subject = "localhost")
    createTrustStore(localhostKeyStore, otherNameKeyStore)

    val config = new InMemoryDirectoryServerConfig(baseDn)
    config.setListenerConfigs(
      ldapsListener("localhost", localhostKeyStore),
      ldapsListener("other-name", otherNameKeyStore),
      ldapsListener("second-other-name", otherNameKeyStore),
      ldapsListener("untrusted", untrustedKeyStore)
    )
    new InMemoryDirectoryServer(config)
  }

  private def localhostServerPort = server.getListenPort("localhost")
  private def otherNameServerPort = server.getListenPort("other-name")
  private def secondOtherNameServerPort = server.getListenPort("second-other-name")
  private def untrustedServerPort = server.getListenPort("untrusted")

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    originalTrustStoreProperties = trustStoreProperties.map(name => name -> Option(System.getProperty(name)))
    server.startListening()
    server.add(s"dn: $baseDn", "objectClass: top", "objectClass: domain", "dc: example")
  }

  override protected def afterAll(): Unit = {
    server.shutDown(true)
    originalTrustStoreProperties.foreach {
      case (name, Some(value)) => System.setProperty(name, value)
      case (name, None)        => System.clearProperty(name)
    }
    certificatesDir.delete(swallowIOExceptions = true)
    super.afterAll()
  }

  private def generateKeyStore(name: String, subject: String) = {
    val keyStore = certificatesDir / s"$name.p12"
    val output = new ByteArrayOutputStream()
    val resultCode = ManageCertificates.main(
      null,
      output,
      output,
      "generate-self-signed-certificate",
      "--keystore",
      keyStore.pathAsString,
      "--keystore-password",
      password,
      "--keystore-type",
      "PKCS12",
      "--alias",
      "server",
      "--subject-dn",
      s"CN=$subject",
      "--subject-alternative-name-dns",
      subject
    )
    if (resultCode != ResultCode.SUCCESS) {
      throw new IllegalStateException(s"Cannot generate a certificate for $subject: ${output.toString("UTF-8")}")
    }
    keyStore
  }

  private def createTrustStore(keyStores: File*): Unit = {
    val trustStore = KeyStore.getInstance("PKCS12")
    trustStore.load(null, null)
    keyStores.foreach { keyStore =>
      trustStore.setCertificateEntry(keyStore.nameWithoutExtension, certificateFrom(keyStore))
    }
    val trustStoreFile = certificatesDir / "truststore.p12"
    trustStoreFile.outputStream().foreach(trustStore.store(_, password.toCharArray))
    // JSSE loads the default trust store again when these properties change.
    System.setProperty("javax.net.ssl.trustStore", trustStoreFile.pathAsString)
    System.setProperty("javax.net.ssl.trustStorePassword", password)
    System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
  }

  private def certificateFrom(keyStoreFile: File): Certificate = {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStoreFile.inputStream().foreach(keyStore.load(_, password.toCharArray))
    keyStore.getCertificate("server")
  }

  private def ldapsListener(name: String, keyStore: File) = {
    val serverSslUtil = new SSLUtil(
      new KeyStoreKeyManager(keyStore.toJava, password.toCharArray, "PKCS12", "server"),
      new TrustAllTrustManager()
    )
    val clientSslUtil = new SSLUtil(new TrustAllTrustManager())
    InMemoryListenerConfig.createLDAPSConfig(
      name,
      null,
      0,
      serverSslUtil.createSSLServerSocketFactory(),
      clientSslUtil.createSSLSocketFactory()
    )
  }

  // The host must be a name. The SDK accepts every certificate of a loopback IP address.
  private def ldapsHost(port: Int) =
    LdapHost.from(s"ldaps://localhost:$port").getOrElse(throw new IllegalStateException("Invalid LDAP host"))

  private def singleServer(port: Int) = ConnectionMethod.SingleServer(ldapsHost(port))

  private def severalServers(haMethod: HaMethod, firstPort: Int, otherPorts: Int*) =
    ConnectionMethod.SeveralServers(NonEmptyList.of(ldapsHost(firstPort), otherPorts.map(ldapsHost)*), haMethod)

  private def config(connectionMethod: ConnectionMethod) = LdapConnectionConfig(
    poolName = LdapService.Name("ldaps-test"),
    connectionMethod = connectionMethod,
    poolSize = positiveInt(1),
    connectionHealthCheckInterval = Refined.unsafeApply(120 seconds),
    connectionMaxAge = Refined.unsafeApply(120 seconds),
    connectionTimeout = Refined.unsafeApply(5 seconds),
    requestTimeout = Refined.unsafeApply(5 seconds),
    trustAllCerts = false,
    sslHostnameVerification = true,
    bindRequestUser = BindRequestUser.Anonymous,
    ignoreLdapConnectivityProblems = false
  )

  private def connect(connectionConfig: LdapConnectionConfig) = {
    var connectionResult: Option[Either[ConnectionError, Unit]] = None
    withConnection(connectionConfig) { result =>
      connectionResult = Some(result.map(_ => ()))
    }
    connectionResult.getOrElse(throw new IllegalStateException("No connection result"))
  }

  private def withConnection(connectionConfig: LdapConnectionConfig)(
      test: Either[ConnectionError, UnboundidLdapConnectionPool] => Unit
  ): Unit = {
    val poolProvider = new UnboundidLdapConnectionPoolProvider
    try {
      test(
        UnboundidLdapConnectionPoolProvider
          .connectWithOptionalBindingTest(poolProvider, connectionConfig)
          .runSyncUnsafe(1 minute)
      )
    } finally poolProvider.close().runSyncUnsafe(1 minute)
  }

  private def assertConnectsAndSearches(connectionConfig: LdapConnectionConfig): Unit = {
    withConnection(connectionConfig) { result =>
      inside(result) { case Right(pool) =>
        inside(search(pool)) { case Right(Right(entries)) =>
          entries.map(_.getDN) should be(List(baseDn))
        }
      }
    }
  }

  private def searchError(result: Either[Throwable, Either[SearchResult, List[SearchResultEntry]]]): String =
    result match {
      case Left(ex)                  => Iterator.iterate(ex)(_.getCause).takeWhile(_ != null).take(10).mkString(" / ")
      case Right(Left(searchResult)) => searchResult.toString
      case Right(Right(_))           => fail("The search was expected to fail")
    }

  private def search(pool: UnboundidLdapConnectionPool) = {
    pool
      .process(new SearchRequest(_, baseDn, SearchScope.BASE, "(objectClass=*)"), Refined.unsafeApply(5 seconds))
      .attempt
      .runSyncUnsafe(1 minute)
  }

}
