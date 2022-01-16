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
package tech.beshu.ror.utils

import java.io.{ByteArrayInputStream, FileInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Base64
import cats.effect.{IO, Resource}

import javax.net.ssl.{SSLEngine, TrustManagerFactory}
import org.apache.logging.log4j.scala.Logging
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import tech.beshu.ror.configuration.SslConfiguration

import scala.util.{Failure, Success, Try}


/**
  * Created by sscarduzio on 02/07/2017.
  */
object SSLCertParser extends Logging {

  def run(sslContextCreator: SSLContextCreator,
          config: SslConfiguration): Unit = {
    Try(loadKeyAndCertificate(config)
      .use { case (cert, key) => IO(sslContextCreator.mkSSLContext(cert, key))}
      .unsafeRunSync()) match {
      case Success(_) =>
      case Failure(ex) =>
        logger.error("ROR SSL: Failed to load SSL certs and keys from JKS Keystore! " + ex.getClass.getSimpleName + ": " + ex.getMessage, ex)
    }
  }

  def validateProtocolAndCiphers(eng: SSLEngine, config: SslConfiguration): Boolean =
    trySetProtocolsAndCiphers(eng, config)
      .fold(
        ex => {
          logger.error("ROR SSL: cannot validate SSL protocols and ciphers! " + ex.getClass.getSimpleName + ": " + ex.getMessage, ex)
          false
        },
        _ => true
      )

  def customTrustManagerFrom(config: SslConfiguration): Option[TrustManagerFactory] = {
    config.truststoreFile
      .flatMap { file =>
        logger.info(s"Using custom truststore: '${file.value.getName}'")
        Try(Resource.fromAutoCloseable(IO(new FileInputStream(file.value)))
          .use(loadTrustedCerts(config))
          .unsafeRunSync()) match {
          case Success(trustmanager) => Some(trustmanager)
          case Failure(ex) =>
            logger.error("ROR SSL: Failed to load trusted SSL certs from provided JKS Keystore! " + ex.getClass.getSimpleName + ": " + ex.getMessage, ex)
            None
        }
      }
  }

  private def loadKeyAndCertificate(config: SslConfiguration) = {
    for {
      keystore <- loadKeystore(config)
      alias <- prepareAlias(keystore, config)
      privateKey <- extractPrivateKey(keystore, alias, config)
      certChain <- extractCertChain(keystore, alias)
    } yield (certChain, privateKey)
  }

  private def loadKeystore(config: SslConfiguration) =
    Resource.fromAutoCloseable(IO(new FileInputStream(config.keystoreFile.value))).map { keystoreInputStream =>
      val keystore = if (config.fipsCompliant) {
        logger.info("ROR SSL: attempting with BCFKS keystore..")
        java.security.KeyStore.getInstance("BCFKS", "BCFIPS")
      } else {
        logger.info("ROR SSL: attempting with JKS keystore..")
        java.security.KeyStore.getInstance("JKS")
      }
      keystore.load(
        keystoreInputStream,
        config.keystorePassword.map(_.value.toCharArray).orNull
      )
      keystore
    }

  private def prepareAlias(keystore: KeyStore, config: SslConfiguration) = Resource.pure[IO, String] {
    config.keyAlias match {
      case None if keystore.aliases().hasMoreElements =>
        val firstAlias = keystore.aliases().nextElement()
        logger.info(s"ROR SSL: ssl.key_alias not configured, took first alias in keystore: $firstAlias")
        firstAlias
      case None =>
        throw MalformedSslSettings("No alias found, therefore key found in keystore!")
      case Some(keyAlias) =>
        keyAlias.value
    }
  }

  private def extractPrivateKey(keystore: KeyStore, alias: String, config: SslConfiguration) = {
    val key = Option(keystore.getKey(
      alias,
      config.keyPass.map(_.value.toCharArray).orNull
    )) match {
      case Some(value) => value
      case None => throw MalformedSslSettings("Private key not found in keystore for alias: " + alias)
    }

    // Create a PEM of the private key
    val privateKey =
      s"""
         |---BEGIN PRIVATE KEY---
         |${Base64.getEncoder.encodeToString(key.getEncoded)}
         |---END PRIVATE KEY---
       """.stripMargin
    logger.info(s"ROR SSL: Discovered key from keystore $privateKey")
    Resource.fromAutoCloseable(IO(new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8))))
  }

  private def extractCertChain(keystore: KeyStore, alias: String) = {
    // Create a PEM of the certificate chain
    val certString = (c: Certificate) =>
      s"""
         |-----BEGIN CERTIFICATE-----
         |${Base64.getEncoder.encodeToString(c.getEncoded)}
         |-----END CERTIFICATE-----
       """.stripMargin
    val certChain = keystore.getCertificateChain(alias).map(certString).mkString("\n")
    logger.info(s"ROR SSL: Discovered cert chain from keystore $certChain")
    Resource.fromAutoCloseable(IO(new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8))))
  }

  private def loadTrustedCerts(config: SslConfiguration)(truststoreInputStream: InputStream) = IO {
    val truststore = if (config.fipsCompliant) {
      java.security.KeyStore.getInstance("BCFKS", "BCFIPS")
    } else {
      java.security.KeyStore.getInstance(KeyStore.getDefaultType)
    }
    truststore.load(
      truststoreInputStream,
      config.truststorePassword.map(_.value.toCharArray).orNull
    )
    val trustManagerFactory = if (config.fipsCompliant) {
      logger.info("Using TrustManagerFactory with BouncyCastle provider")
      TrustManagerFactory.getInstance("X509", BouncyCastleJsseProvider.PROVIDER_NAME)
    } else {
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    }

    trustManagerFactory.init(truststore)
    trustManagerFactory
  }

  private def trySetProtocolsAndCiphers(eng: SSLEngine, config: SslConfiguration) = Try {
    logger.info("ROR SSL: Available ciphers: " + eng.getEnabledCipherSuites.mkString(","))
    if (config.allowedCiphers.nonEmpty) {
      eng.setEnabledCipherSuites(config.allowedCiphers.map(_.value).toArray)
      logger.info("ROR SSL: Restricting to ciphers: " + eng.getEnabledCipherSuites.mkString(","))
    }
    logger.info("ROR SSL: Available SSL protocols: " + eng.getEnabledProtocols.mkString(","))
    if (config.allowedProtocols.nonEmpty) {
      eng.setEnabledProtocols(config.allowedProtocols.map(_.value).toArray)
      logger.info("ROR SSL: Restricting to SSL protocols: " + eng.getEnabledProtocols.mkString(","))
    }
  }

  trait SSLContextCreator {
    def mkSSLContext(certChain: InputStream, privateKey: InputStream): Unit
  }
}

final case class MalformedSslSettings(message: String) extends Exception(message)