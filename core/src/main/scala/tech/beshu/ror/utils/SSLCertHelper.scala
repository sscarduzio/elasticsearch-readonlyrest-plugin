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
import io.netty.buffer.ByteBufAllocator
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{ClientAuth, SslContext, SslContextBuilder}
import org.apache.logging.log4j.scala.Logging
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import tech.beshu.ror.configuration.SslConfiguration
import tech.beshu.ror.configuration.SslConfiguration.ClientCertificateConfiguration.TruststoreBasedConfiguration
import tech.beshu.ror.configuration.SslConfiguration.ServerCertificateConfiguration.KeystoreBasedConfiguration
import tech.beshu.ror.configuration.SslConfiguration._

import java.io.{File, FileInputStream, FileReader, IOException}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, PrivateKey}
import javax.net.ssl.{KeyManagerFactory, TrustManagerFactory}
import scala.collection.JavaConverters._
import scala.language.{existentials, implicitConversions}
import scala.util.Try

object SSLCertHelper extends Logging {

  def prepareClientSSLContext(sslConfiguration: SslConfiguration, fipsCompliant: Boolean, certificateVerificationEnabled: Boolean): SslContext = {
    if (certificateVerificationEnabled) {
      sslConfiguration.clientCertificateConfiguration match {
        case Some(truststoreBasedConfiguration: TruststoreBasedConfiguration) =>
          SslContextBuilder.forClient.trustManager(getTrustManagerFactory(truststoreBasedConfiguration, fipsCompliant)).build()
        case Some(fileBasedConfiguration: ClientCertificateConfiguration.FileBasedConfiguration) =>
          SslContextBuilder.forClient.trustManager(getTrustedCertificatesFromPemFile(fileBasedConfiguration).toIterable.asJava).build()
        case None =>
          throw new Exception("Client Authentication could not be enabled because trust certificates has not been configured")
      }
    } else {
      SslContextBuilder.forClient.trustManager(InsecureTrustManagerFactory.INSTANCE).build
    }
  }

  def prepareServerSSLContext(sslConfiguration: SslConfiguration, fipsCompliant: Boolean, clientAuthenticationEnabled: Boolean): SslContext = {
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
            sslConfiguration.clientCertificateConfiguration match {
              case Some(truststoreBasedConfiguration: TruststoreBasedConfiguration) =>
                sslCtxBuilder.trustManager(getTrustManagerFactory(truststoreBasedConfiguration, fipsCompliant))
              case Some(fileBasedConfiguration: ClientCertificateConfiguration.FileBasedConfiguration) =>
                sslCtxBuilder.trustManager(getTrustedCertificatesFromPemFile(fileBasedConfiguration).toIterable.asJava)
              case None =>
                throw new Exception("Client Authentication could not be enabled because trust certificates has not been configured")
            }
          }
          if (sslConfiguration.allowedProtocols.nonEmpty) {
            sslCtxBuilder.protocols(sslConfiguration.allowedProtocols.map(_.value).asJava)
          }
          sslCtxBuilder.build()
        case Left(exception: IOException) =>
          throw UnableToLoadDataFromProvidedFilesException(exception)
        case Left(exception) =>
          throw UnableToInitializeSslContextBuilderUsingProvidedFiles(exception)
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

  def isPEMHandlingAvailable: Boolean = {
    Try {
      Class.forName("org.bouncycastle.openssl.PEMParser")
    }
      .isSuccess
  }

  def getTrustedCertificatesFromPemFile(fileBasedConfiguration: ClientCertificateConfiguration.FileBasedConfiguration): Array[X509Certificate] = {
    loadCertificateChain(fileBasedConfiguration.clientTrustedCertificateFile.value)
      .attempt
      .map {
        case Right(certificateChain) => certificateChain
        case Left(exception) =>
          throw UnableToLoadDataFromProvidedFilesException(exception)
      }
      .unsafeRunSync()
  }

  def getTrustManagerFactory(truststoreBasedConfiguration: TruststoreBasedConfiguration, fipsCompliant: Boolean): TrustManagerFactory = {
    loadTruststore(truststoreBasedConfiguration, fipsCompliant)
      .map { truststore =>
        val tmf = getTrustManagerFactoryInstance(fipsCompliant)
        tmf.init(truststore)
        tmf
      }
      .attempt
      .map {
        case Right(tmf) => tmf
        case Left(exception) =>
          throw UnableToInitializeTrustManagerFactoryUsingProvidedTruststore(exception)
      }
      .unsafeRunSync()
  }

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

  private def loadKeystore(keystoreBasedConfiguration: KeystoreBasedConfiguration, fipsCompliant: Boolean): IO[KeyStore] = {
    for {
      _ <- IO(logger.info("Preparing keystore..."))
      keystore <- loadKeystoreFromFile(keystoreBasedConfiguration.keystoreFile.value, keystoreBasedConfiguration.keystorePassword, fipsCompliant)
    } yield keystore
  }

  private def loadTruststore(truststoreBasedConfiguration: TruststoreBasedConfiguration, fipsCompliant: Boolean): IO[KeyStore] = {
    for {
      _ <- IO(logger.info("Preparing truststore..."))
      truststore <- loadKeystoreFromFile(truststoreBasedConfiguration.truststoreFile.value, truststoreBasedConfiguration.truststorePassword, fipsCompliant)
    } yield truststore
  }

  private def prepareAlias(keystore: KeyStore, config: KeystoreBasedConfiguration) =
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

  private def getFipsCompliantKeyManagerFactory(keystoreBasedConfiguration: KeystoreBasedConfiguration): IO[KeyManagerFactory] = {
    loadKeystore(keystoreBasedConfiguration, fipsCompliant = true)
      .map { keystore =>
        if (keystoreBasedConfiguration.keyPass.isDefined) {
          logger.warn("ROR configuration parameter key_pass is declared however it won't be used in this mode. In this case password for specific key MUST be the same as keystore password")
        }
        removeAllAliasesFromKeystoreBesidesOne(keystore, prepareAlias(keystore, keystoreBasedConfiguration))
        val kmf = getKeyManagerFactoryInstance(fipsCompliant = true)
        kmf.init(keystore, keystoreBasedConfiguration.keystorePassword)
        kmf
      }
  }

  private def getPrivateKeyAndCertificateChainFromPemFiles(fileBasedConfiguration: ServerCertificateConfiguration.FileBasedConfiguration): IO[(PrivateKey, Array[X509Certificate])] = {
    for {
      privateKey <- loadPrivateKey(fileBasedConfiguration.serverCertificateKeyFile.value)
      certificateChain <- loadCertificateChain(fileBasedConfiguration.serverCertificateFile.value)
    } yield (privateKey, certificateChain)
  }

  private def loadPrivateKey(file: File): IO[PrivateKey] = {
    Resource
      .fromAutoCloseable(IO(new FileReader(file)))
      .use { privateKeyFileReader => IO {
        val pemParser = new PEMParser(privateKeyFileReader)
        val privateKeyInfo = PrivateKeyInfo.getInstance(pemParser.readObject())
        val converter = new JcaPEMKeyConverter()
        converter.getPrivateKey(privateKeyInfo)
      }}
  }

  private def loadCertificateChain(file: File): IO[Array[X509Certificate]] = {
    Resource
      .fromAutoCloseable(IO(new FileInputStream(file)))
      .use { certificateChainFile => IO {
        val certFactory = CertificateFactory.getInstance("X.509")
        certFactory.generateCertificates(certificateChainFile).asScala.toArray.map {
          case cc: X509Certificate => cc
          case _ => throw MalformedSslSettings(s"Certificate chain in $file contains invalid X509 certificate")
        }
      }}
  }

  private def getPrivateKeyAndCertificateChainFromKeystore(keystoreBasedConfiguration: KeystoreBasedConfiguration): IO[(PrivateKey, Array[X509Certificate])] = {
    loadKeystore(keystoreBasedConfiguration, fipsCompliant = false)
      .map { keystore =>
        val alias = prepareAlias(keystore, keystoreBasedConfiguration)
        val privateKey = keystore.getKey(alias, keystoreBasedConfiguration.keyPass.map(_.value.toCharArray).orNull) match {
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

  private def prepareSslContextBuilder(sslConfiguration: SslConfiguration, fipsCompliant: Boolean): IO[SslContextBuilder] = {
    if(fipsCompliant) {
      val keystoreBasedConfiguration = sslConfiguration.serverCertificateConfiguration match {
        case keystoreBasedConfiguration: KeystoreBasedConfiguration => keystoreBasedConfiguration
        case _ => throw new Exception("KeyStore based configuration is required in FIPS compliant mode")
      }
      getFipsCompliantKeyManagerFactory(keystoreBasedConfiguration)
        .map { keyManagerFactory =>
          logger.info("Initializing ROR SSL using SSL provider: " + keyManagerFactory.getProvider.getName)
          SslContextBuilder.forServer(keyManagerFactory)
        }
    } else {
      (sslConfiguration.serverCertificateConfiguration match {
        case fileBasedConfiguration: ServerCertificateConfiguration.FileBasedConfiguration =>
          getPrivateKeyAndCertificateChainFromPemFiles(fileBasedConfiguration)
        case keystoreBasedConfiguration: KeystoreBasedConfiguration =>
          getPrivateKeyAndCertificateChainFromKeystore(keystoreBasedConfiguration)
      }).map { case (privateKey, certificateChain) =>
        logger.info(s"Initializing ROR SSL using default SSL provider ${SslContext.defaultServerProvider().name()}")
        SslContextBuilder.forServer(privateKey, certificateChain.toIterable.asJava)
      }
    }
  }

  final case class UnableToLoadDataFromProvidedFilesException(cause: Throwable) extends Exception(s"Unable to load data from provided files", cause)
  final case class UnableToInitializeSslContextBuilderUsingProvidedFiles(cause: Throwable) extends Exception(s"Unable to initialize Key Manager Factory using provided keystore.", cause)
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
