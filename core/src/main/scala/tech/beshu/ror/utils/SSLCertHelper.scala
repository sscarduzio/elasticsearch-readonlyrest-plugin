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

import cats.effect.{IO, Resource}
import cats.implicits._
import io.netty.buffer.ByteBufAllocator
import io.netty.handler.ssl.{SslContext, SslContextBuilder}
import org.apache.logging.log4j.scala.Logging
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import tech.beshu.ror.configuration.SslConfiguration
import tech.beshu.ror.configuration.SslConfiguration.{KeystoreFile, KeystorePassword, TruststorePassword}

import java.io.{FileInputStream, IOException}
import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, TrustManagerFactory}
import scala.collection.JavaConverters._
import scala.language.{existentials, implicitConversions}
import scala.util.{Failure, Success, Try}

object SSLCertHelper extends Logging {

  private def getKeyManagerFactoryInstance(fipsCompliant: Boolean) = {
    if(fipsCompliant) {
      KeyManagerFactory.getInstance("X509", BouncyCastleJsseProvider.PROVIDER_NAME)
    } else {
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    }
  }

  private def getTrustManagerFactoryInstance(fipsCompliant: Boolean) = {
    if(fipsCompliant) {
      TrustManagerFactory.getInstance("X509", BouncyCastleJsseProvider.PROVIDER_NAME)
    } else {
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    }
  }

  private def loadKeystore(sslConfiguration: SslConfiguration, fipsCompliant: Boolean) = {
    Resource
      .fromAutoCloseable(IO(new FileInputStream(sslConfiguration.keystoreFile.value)))
      .use { keystoreFile => IO {
        val keystore = if (fipsCompliant) {
          logger.info("Trying to load keystore in FIPS compliant BCFKS format...")
          java.security.KeyStore.getInstance("BCFKS", "BCFIPS")
        } else {
          logger.info("Trying to load keystore in JKS or PKCS#12 format...")
          java.security.KeyStore.getInstance("JKS")
        }
        keystore.load(keystoreFile, sslConfiguration.keystorePassword)
        keystore
      }
      }
  }

  private def loadTruststore(sslConfiguration: SslConfiguration, fipsCompliant: Boolean) = {
    sslConfiguration.truststoreFile
      .map { truststoreFileName =>
        Resource
          .fromAutoCloseable(IO(new FileInputStream(truststoreFileName.value)))
          .use { truststoreFile =>
            IO {
              val keystore = if (fipsCompliant) {
                logger.info("Trying to load truststore in FIPS compliant BCFKS format...")
                java.security.KeyStore.getInstance("BCFKS", "BCFIPS")
              } else {
                logger.info("Trying to load truststore in JKS or PKCS#12 format...")
                java.security.KeyStore.getInstance("JKS")
              }
              keystore.load(truststoreFile, sslConfiguration.truststorePassword)
              keystore
            }
          }
      }.sequence
  }

  private def prepareAlias(keystore: KeyStore, config: SslConfiguration) =
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

  private def removeAllAliasesFromKeystoreBesidesOne(keystore: KeyStore, alias: String): Unit = {
    val unnecessaryAliases = keystore.aliases().asScala.toSet.-(alias)
    unnecessaryAliases.foreach(keystore.deleteEntry)
  }

  private def getKeyManagerFactory(sslConfiguration: SslConfiguration, fipsCompliant: Boolean): IO[KeyManagerFactory] = {
    loadKeystore(sslConfiguration, fipsCompliant)
      .map { keystore =>
        removeAllAliasesFromKeystoreBesidesOne(keystore, prepareAlias(keystore, sslConfiguration))
        val kmf = getKeyManagerFactoryInstance(fipsCompliant)
        kmf.init(keystore, sslConfiguration.keystorePassword)
        kmf
      }
  }

  private def trySetProtocolsAndCiphersInsideNewEngine(sslContextBuilder: SslContextBuilder, config: SslConfiguration) = Try {
    val sslEngine = sslContextBuilder.build().newEngine(ByteBufAllocator.DEFAULT)
    logger.info("ROR SSL: Available ciphers: " + sslEngine.getEnabledCipherSuites.mkString(","))
    if (config.allowedCiphers.nonEmpty) {
      sslEngine.setEnabledCipherSuites(config.allowedCiphers.map(_.value).toArray)
      logger.info("ROR SSL: Restricting to ciphers: " + sslEngine.getEnabledCipherSuites.mkString(","))
    }
    logger.info("ROR SSL: Available SSL protocols: " + sslEngine.getEnabledProtocols.mkString(","))
    if (config.allowedProtocols.nonEmpty) {
      sslEngine.setEnabledProtocols(config.allowedProtocols.map(_.value).toArray)
      logger.info("ROR SSL: Restricting to SSL protocols: " + sslEngine.getEnabledProtocols.mkString(","))
    }
  }

  def getTrustManagerFactory(sslConfiguration: SslConfiguration, fipsCompliant: Boolean): TrustManagerFactory = {
    Try(loadTruststore(sslConfiguration, fipsCompliant)
      .map {
        _.map { truststore =>
          val tmf = getTrustManagerFactoryInstance(fipsCompliant)
          tmf.init(truststore)
          tmf
        }
      }
      .unsafeRunSync()) match {
      case Success(kmf) => kmf.getOrElse(throw TrustManagerNotConfiguredException)
      case Failure(exception) =>
        throw UnableToInitializeTrustManagerFactoryUsingProvidedTruststore(exception.getMessage)
    }
  }

  def prepareSSLContext(sslConfiguration: SslConfiguration, fipsCompliant: Boolean): SslContext = {
    Try(SSLCertHelper
      .getKeyManagerFactory(sslConfiguration, fipsCompliant)
      .unsafeRunSync()) match {
      case Success(keyManagerFactory) =>
        val sslCtxBuilder = SslContextBuilder.forServer(keyManagerFactory)
        logger.info("ROR Internode using SSL provider: " + keyManagerFactory.getProvider.getName)
        validateProtocolAndCiphers(sslCtxBuilder, sslConfiguration)
        if (sslConfiguration.allowedCiphers.nonEmpty) {
          sslCtxBuilder.ciphers(sslConfiguration.allowedCiphers.map(_.value).asJava)
        }

        if (sslConfiguration.allowedProtocols.nonEmpty) {
          sslCtxBuilder.protocols(sslConfiguration.allowedProtocols.map(_.value).asJava)
        }
        sslCtxBuilder.build()
      case Failure(exception: IOException) =>
        throw UnableToLoadDataFromProvidedKeystoreException(sslConfiguration.keystoreFile, exception.getMessage)
      case Failure(exception) =>
        throw UnableToInitializeKeyManagerFactoryUsingProvidedKeystore(exception.getMessage)
    }
  }

  def validateProtocolAndCiphers(sslContextBuilder: SslContextBuilder, config: SslConfiguration): Boolean =
    trySetProtocolsAndCiphersInsideNewEngine(sslContextBuilder: SslContextBuilder, config)
      .fold(
        ex => {
          logger.error("ROR SSL: cannot validate SSL protocols and ciphers! " + ex.getClass.getSimpleName + ": " + ex.getMessage, ex)
          false
        },
        _ => true
      )

  final case class UnableToLoadDataFromProvidedKeystoreException(keystoreName: KeystoreFile, exceptionMessage: String) extends Exception(s"Unable to load data from provided keystore [${keystoreName.value.getName}]. $exceptionMessage")
  final case class UnableToInitializeKeyManagerFactoryUsingProvidedKeystore(exceptionMessage: String) extends Exception(s"Unable to initialize Key Manager Factory using provided keystore. $exceptionMessage")
  final case class UnableToInitializeTrustManagerFactoryUsingProvidedTruststore(exceptionMessage: String) extends Exception(s"Unable to initialize Trust Manager Factory using provided truststore. $exceptionMessage")
  final case class MalformedSslSettings(str: String) extends Exception(str)
  case object TrustManagerNotConfiguredException extends Exception("Trust manager has not been configured!")

  private implicit def optionalKeystorePasswordToCharArray(keystorePassword: Option[KeystorePassword]): Array[Char] = {
    keystorePassword.map(_.value.toCharArray).orNull
  }

  private implicit def optionalTruststorePasswordToCharArray(truststorePassword: Option[TruststorePassword]): Array[Char] = {
    truststorePassword.map(_.value.toCharArray).orNull
  }

}
