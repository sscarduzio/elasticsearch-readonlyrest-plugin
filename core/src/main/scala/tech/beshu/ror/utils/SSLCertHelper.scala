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
import io.netty.handler.ssl.{ClientAuth, SslContext, SslContextBuilder}
import org.apache.logging.log4j.scala.Logging
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import tech.beshu.ror.configuration.SslConfiguration
import tech.beshu.ror.configuration.SslConfiguration.{KeystoreFile, KeystorePassword, TruststorePassword}

import java.io.{File, FileInputStream, IOException}
import java.security.cert.X509Certificate
import java.security.{KeyStore, PrivateKey}
import javax.net.ssl.{KeyManagerFactory, TrustManagerFactory}
import scala.collection.JavaConverters._
import scala.language.{existentials, implicitConversions}
import scala.util.Try

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

  private def loadKeystoreFromFile(keystoreFile: File, password: Array[Char], fipsCompliant: Boolean): IO[KeyStore] = {
    Resource
      .fromAutoCloseable(IO(new FileInputStream(keystoreFile)))
      .use { keystoreFile => IO {
        val keystore = if (fipsCompliant) {
          logger.info("Trying to load data in FIPS compliant BCFKS format...")
          java.security.KeyStore.getInstance("BCFKS", "BCFIPS")
        } else {
          logger.info("Trying to load data in JKS or PKCS#12 format...")
          java.security.KeyStore.getInstance("JKS")
        }
        keystore.load(keystoreFile, password)
        keystore
      }
      }
  }

  private def loadKeystore(sslConfiguration: SslConfiguration, fipsCompliant: Boolean) = {
    for {
      _ <- IO(logger.info("Preparing keystore..."))
      keystore <- loadKeystoreFromFile(sslConfiguration.keystoreFile.value, sslConfiguration.keystorePassword, fipsCompliant)
    } yield keystore
  }

  private def loadTruststore(sslConfiguration: SslConfiguration, fipsCompliant: Boolean) = {
    sslConfiguration.truststoreFile
      .map { truststoreFileName =>
        for {
          _ <- IO(logger.info("Preparing truststore..."))
          truststore <- loadKeystoreFromFile(truststoreFileName.value, sslConfiguration.truststorePassword, fipsCompliant)
        } yield truststore
      }.sequence
  }

  private def prepareAlias(keystore: KeyStore, config: SslConfiguration) =
    config.keyAlias match {
      case None if keystore.aliases().hasMoreElements =>
        val firstAlias = keystore.aliases().nextElement()
        logger.info(s"ROR SSL: ssl.key_alias not configured, took first alias in keystore: $firstAlias")
        firstAlias
      case None =>
        throw MalformedSslSettings("Key not found in provided keystore!")
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
        if (sslConfiguration.keyPass.isDefined) {
          logger.warn("ROR configuration parameter key_pass is declared however it won't be used in this mode. In this case password for specific key MUST be the same as keystore password")
        }
        removeAllAliasesFromKeystoreBesidesOne(keystore, prepareAlias(keystore, sslConfiguration))
        val kmf = getKeyManagerFactoryInstance(fipsCompliant)
        kmf.init(keystore, sslConfiguration.keystorePassword)
        kmf
      }
  }

  private def getPrivateKeyAndCertificateChain(sslConfiguration: SslConfiguration): IO[(PrivateKey, Array[X509Certificate])] = {
    loadKeystore(sslConfiguration, fipsCompliant = false)
      .map { keystore =>
        val alias = prepareAlias(keystore, sslConfiguration)
        val privateKey = keystore.getKey(alias, sslConfiguration.keyPass.map(_.value.toCharArray).orNull) match {
          case pk: PrivateKey => pk
          case _ => throw MalformedSslSettings(s"Configured key with alias=$alias is not a private key")
        }
        val certificateChain = keystore.getCertificateChain(alias).map {
          case cc: X509Certificate => cc
          case _ => throw MalformedSslSettings(s"Certificate chain for alias=$alias is not X509 certificate")
        }
        (privateKey, certificateChain)
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
    loadTruststore(sslConfiguration, fipsCompliant)
      .map {
        _.map { truststore =>
          val tmf = getTrustManagerFactoryInstance(fipsCompliant)
          tmf.init(truststore)
          tmf
        }
      }
      .attempt
      .map {
        case Right(kmf) => kmf.getOrElse(throw TrustManagerNotConfiguredException)
        case Left(exception) =>
          throw UnableToInitializeTrustManagerFactoryUsingProvidedTruststore(exception)
      }
      .unsafeRunSync()
  }

  private def prepareSslContextBuilder(sslConfiguration: SslConfiguration, fipsCompliant: Boolean): IO[SslContextBuilder] = {
    if(fipsCompliant) {
      SSLCertHelper
        .getKeyManagerFactory(sslConfiguration, fipsCompliant)
        .map { keyManagerFactory =>
            logger.info("Initializing ROR SSL using SSL provider: " + keyManagerFactory.getProvider.getName)
            SslContextBuilder.forServer(keyManagerFactory)
        }
    } else {
      SSLCertHelper
        .getPrivateKeyAndCertificateChain(sslConfiguration)
        .map { case (privateKey, certificateChain) =>
          logger.info(s"Initializing ROR SSL using default SSL provider ${SslContext.defaultServerProvider().name()}")
          SslContextBuilder.forServer(privateKey, certificateChain.toIterable.asJava)
        }
    }
  }

  def prepareSSLContext(sslConfiguration: SslConfiguration, fipsCompliant: Boolean, clientAuthenticationEnabled: Boolean): SslContext = {
    prepareSslContextBuilder(sslConfiguration, fipsCompliant)
      .attempt
      .map {
        case Right(sslCtxBuilder) =>
          areProtocolAndCiphersValid(sslCtxBuilder, sslConfiguration)
          if (sslConfiguration.allowedCiphers.nonEmpty) {
            sslCtxBuilder.ciphers(sslConfiguration.allowedCiphers.map(_.value).asJava)
          }
          if (clientAuthenticationEnabled) {
            sslCtxBuilder.clientAuth(ClientAuth.REQUIRE)
            sslCtxBuilder.trustManager(SSLCertHelper.getTrustManagerFactory(sslConfiguration, fipsCompliant))
          }
          if (sslConfiguration.allowedProtocols.nonEmpty) {
            sslCtxBuilder.protocols(sslConfiguration.allowedProtocols.map(_.value).asJava)
          }
          sslCtxBuilder.build()
        case Left(exception: IOException) =>
          throw UnableToLoadDataFromProvidedKeystoreException(sslConfiguration.keystoreFile, exception)
        case Left(exception) =>
          throw UnableToInitializeSslContextBuilderUsingProvidedKeystore(exception)
      }
      .unsafeRunSync()
  }

  def areProtocolAndCiphersValid(sslContextBuilder: SslContextBuilder, config: SslConfiguration): Boolean =
    trySetProtocolsAndCiphersInsideNewEngine(sslContextBuilder: SslContextBuilder, config)
      .fold(
        ex => {
          logger.error("ROR SSL: cannot validate SSL protocols and ciphers! " + ex.getClass.getSimpleName + ": " + ex.getMessage, ex)
          false
        },
        _ => true
      )

  final case class UnableToLoadDataFromProvidedKeystoreException(keystoreName: KeystoreFile, cause: Throwable) extends Exception(s"Unable to load data from provided keystore [${keystoreName.value.getName}]", cause)
  final case class UnableToInitializeSslContextBuilderUsingProvidedKeystore(cause: Throwable) extends Exception(s"Unable to initialize Key Manager Factory using provided keystore.", cause)
  final case class UnableToInitializeTrustManagerFactoryUsingProvidedTruststore(cause: Throwable) extends Exception(s"Unable to initialize Trust Manager Factory using provided truststore.", cause)
  final case class MalformedSslSettings(str: String) extends Exception(str)
  case object TrustManagerNotConfiguredException extends Exception("Trust manager has not been configured!")

  private implicit def optionalKeystorePasswordToCharArray(keystorePassword: Option[KeystorePassword]): Array[Char] = {
    keystorePassword.map(_.value.toCharArray).orNull
  }

  private implicit def optionalTruststorePasswordToCharArray(truststorePassword: Option[TruststorePassword]): Array[Char] = {
    truststorePassword.map(_.value.toCharArray).orNull
  }

}
