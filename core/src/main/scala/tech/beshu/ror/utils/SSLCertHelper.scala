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

import better.files.*
import cats.effect.{IO, Resource}
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{ClientAuth, SslContext, SslContextBuilder}
import org.apache.logging.log4j.scala.Logging
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import tech.beshu.ror.settings.es.SslSettings
import tech.beshu.ror.settings.es.SslSettings.*
import tech.beshu.ror.settings.es.SslSettings.ClientCertificateSettings.TruststoreBasedSettings
import tech.beshu.ror.settings.es.SslSettings.ServerCertificateSettings.KeystoreBasedSettings
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.RorSslSettings.IsSslFipsCompliant

import java.io.{FileInputStream, FileReader, IOException}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, PrivateKey}
import javax.net.ssl.{KeyManagerFactory, SNIServerName, SSLEngine, TrustManagerFactory}
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions
import scala.util.Try

object SSLCertHelper extends Logging {

  def prepareSSLEngine(sslContext: SslContext,
                       hostAndPort: HostAndPort,
                       channelHandlerContext: ChannelHandlerContext,
                       serverName: Option[SNIServerName],
                       enableHostnameVerification: Boolean,
                       fipsCompliant: Boolean): SSLEngine = {
    val sslEngine = if (fipsCompliant || !enableHostnameVerification) {
      sslContext
        .newEngine(channelHandlerContext.alloc(), hostAndPort.host, hostAndPort.port)
    } else {
      sslContext
        .newEngine(channelHandlerContext.alloc(), hostAndPort.host, hostAndPort.port)
        .enableHostnameVerification
    }
    serverName.foreach { name =>
      val sslParameters = sslEngine.getSSLParameters
      sslParameters.setServerNames(List(name).asJava)
      sslEngine.setSSLParameters(sslParameters)
    }
    sslEngine
  }

  def prepareClientSSLContext(sslSettings: SslSettings): SslContext = {
    val builder =
      if (sslSettings.certificateVerificationEnabled) {
        sslSettings.clientCertificateSettings match {
          case Some(truststoreBasedSettings: TruststoreBasedSettings) =>
            SslContextBuilder.forClient.trustManager(getTrustManagerFactory(truststoreBasedSettings, sslSettings.fipsMode.isSslFipsCompliant))
          case Some(fileBasedSettings: ClientCertificateSettings.FileBasedSettings) =>
            SslContextBuilder.forClient.trustManager(getTrustedCertificatesFromPemFile(fileBasedSettings).toList.asJava)
          case None =>
            throw new Exception("Client Authentication could not be enabled because trust certificates has not been configured")
        }
      } else {
        SslContextBuilder.forClient.trustManager(InsecureTrustManagerFactory.INSTANCE)
      }
    val result = if (sslSettings.fipsMode.isSslFipsCompliant) {
      val keystoreBasedSettings = sslSettings.serverCertificateSettings match {
        case keystoreBasedSettings: KeystoreBasedSettings => keystoreBasedSettings
        case _ => throw new Exception("KeyStore based settings is required in FIPS compliant mode")
      }
      getFipsCompliantKeyManagerFactory(keystoreBasedSettings)
        .map { keyManagerFactory =>
          logger.info(s"Initializing ROR SSL using SSL provider: ${keyManagerFactory.getProvider.getName.show}")
          builder.keyManager(keyManagerFactory)
        }
    } else {
      (sslSettings.serverCertificateSettings match {
        case fileBasedSettings: ServerCertificateSettings.FileBasedSettings =>
          getPrivateKeyAndCertificateChainFromPemFiles(fileBasedSettings)
        case keystoreBasedSettings: KeystoreBasedSettings =>
          getPrivateKeyAndCertificateChainFromKeystore(keystoreBasedSettings)
      }).map { case (privateKey, certificateChain) =>
        logger.info(s"Initializing ROR SSL using default SSL provider ${SslContext.defaultServerProvider().name().show}")
        builder.keyManager(privateKey, certificateChain.toList.asJava)
      }
    }
    result.unsafeRunSync().build()
  }

  def prepareServerSSLContext(sslSettings: SslSettings, clientAuthenticationEnabled: Boolean): SslContext = {
    prepareSslContextBuilder(sslSettings)
      .attempt
      .map {
        case Right(sslCtxBuilder) =>
          areProtocolAndCiphersValid(sslCtxBuilder, sslSettings)
          if (sslSettings.allowedCiphers.nonEmpty) {
            sslCtxBuilder.ciphers(sslSettings.allowedCiphers.map(_.value).asJava)
          }
          if (clientAuthenticationEnabled) {
            sslCtxBuilder.clientAuth(ClientAuth.REQUIRE)
            sslSettings.clientCertificateSettings match {
              case Some(truststoreBasedSettings: TruststoreBasedSettings) =>
                sslCtxBuilder.trustManager(getTrustManagerFactory(truststoreBasedSettings, sslSettings.fipsMode.isSslFipsCompliant))
              case Some(fileBasedSettings: ClientCertificateSettings.FileBasedSettings) =>
                sslCtxBuilder.trustManager(getTrustedCertificatesFromPemFile(fileBasedSettings).toList.asJava)
              case None =>
                throw new Exception("Client Authentication could not be enabled because trust certificates has not been configured")
            }
          }
          if (sslSettings.allowedProtocols.nonEmpty) {
            sslCtxBuilder.protocols(sslSettings.allowedProtocols.map(_.value).asJava)
          }
          sslCtxBuilder.build()
        case Left(exception: IOException) =>
          throw UnableToLoadDataFromProvidedFilesException(exception)
        case Left(exception) =>
          throw UnableToInitializeSslContextBuilderUsingProvidedFiles(exception)
      }
      .unsafeRunSync()
  }

  def isPEMHandlingAvailable: Boolean = {
    Try {
      Class.forName("org.bouncycastle.openssl.PEMParser")
    }
      .isSuccess
  }

  private def getTrustedCertificatesFromPemFile(fileBasedSettings: ClientCertificateSettings.FileBasedSettings): Array[X509Certificate] = {
    loadCertificateChain(fileBasedSettings.clientTrustedCertificateFile.value)
      .attempt
      .map {
        case Right(certificateChain) => certificateChain
        case Left(exception) =>
          throw UnableToLoadDataFromProvidedFilesException(exception)
      }
      .unsafeRunSync()
  }

  private def getTrustManagerFactory(truststoreBasedSettings: TruststoreBasedSettings, fipsCompliant: Boolean): TrustManagerFactory = {
    loadTruststore(truststoreBasedSettings, fipsCompliant)
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

  private def areProtocolAndCiphersValid(sslContextBuilder: SslContextBuilder,
                                         sslSettings: SslSettings): Boolean =
    trySetProtocolsAndCiphersInsideNewEngine(sslContextBuilder: SslContextBuilder, sslSettings)
      .fold(
        ex => {
          logger.error(s"ROR SSL: cannot validate SSL protocols and ciphers! ${ex.getClass.getSimpleName.show} : ${ex.getMessage.show}", ex)
          false
        },
        _ => true
      )

  private def getKeyManagerFactoryInstance(fipsCompliant: Boolean) = {
    if (fipsCompliant) {
      KeyManagerFactory.getInstance("X509", BouncyCastleJsseProvider.PROVIDER_NAME)
    } else {
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    }
  }

  private def getTrustManagerFactoryInstance(fipsCompliant: Boolean) = {
    if (fipsCompliant) {
      TrustManagerFactory.getInstance("X509", BouncyCastleJsseProvider.PROVIDER_NAME)
    } else {
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    }
  }

  private def loadKeystoreFromFile(keystoreFile: File, password: Array[Char], fipsCompliant: Boolean): IO[KeyStore] = {
    Resource
      .fromAutoCloseable(IO(new FileInputStream(keystoreFile.toJava)))
      .use { keystoreFile =>
        IO {
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

  private def loadKeystore(keystoreBasedSettings: KeystoreBasedSettings, fipsCompliant: Boolean): IO[KeyStore] = {
    for {
      _ <- IO(logger.info("Preparing keystore..."))
      keystore <- loadKeystoreFromFile(keystoreBasedSettings.keystoreFile.value, keystoreBasedSettings.keystorePassword, fipsCompliant)
    } yield keystore
  }

  private def loadTruststore(truststoreBasedSettings: TruststoreBasedSettings, fipsCompliant: Boolean): IO[KeyStore] = {
    for {
      _ <- IO(logger.info("Preparing truststore..."))
      truststore <- loadKeystoreFromFile(truststoreBasedSettings.truststoreFile.value, truststoreBasedSettings.truststorePassword, fipsCompliant)
    } yield truststore
  }

  private def prepareAlias(keystore: KeyStore, settings: KeystoreBasedSettings) =
    settings.keyAlias match {
      case None if keystore.aliases().hasMoreElements =>
        val firstAlias = keystore.aliases().nextElement()
        logger.info(s"ROR SSL: ssl.key_alias not configured, took first alias in keystore: ${firstAlias.show}")
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

  private def getFipsCompliantKeyManagerFactory(keystoreBasedSettings: KeystoreBasedSettings): IO[KeyManagerFactory] = {
    loadKeystore(keystoreBasedSettings, fipsCompliant = true)
      .map { keystore =>
        if (keystoreBasedSettings.keyPass.isDefined) {
          logger.warn("ROR settings parameter key_pass is declared however it won't be used in this mode. In this case password for specific key MUST be the same as keystore password")
        }
        removeAllAliasesFromKeystoreBesidesOne(keystore, prepareAlias(keystore, keystoreBasedSettings))
        val kmf = getKeyManagerFactoryInstance(fipsCompliant = true)
        kmf.init(keystore, keystoreBasedSettings.keystorePassword)
        kmf
      }
  }

  private def getPrivateKeyAndCertificateChainFromPemFiles(fileBasedSettings: ServerCertificateSettings.FileBasedSettings): IO[(PrivateKey, Array[X509Certificate])] = {
    for {
      privateKey <- loadPrivateKey(fileBasedSettings.serverCertificateKeyFile.value)
      certificateChain <- loadCertificateChain(fileBasedSettings.serverCertificateFile.value)
    } yield (privateKey, certificateChain)
  }

  private def loadPrivateKey(file: File): IO[PrivateKey] = {
    Resource
      .fromAutoCloseable(IO(new FileReader(file.toJava)))
      .use { privateKeyFileReader =>
        IO {
          val pemParser = new PEMParser(privateKeyFileReader)
          val privateKeyInfo = PrivateKeyInfo.getInstance(pemParser.readObject())
          val converter = new JcaPEMKeyConverter()
          converter.getPrivateKey(privateKeyInfo)
        }
      }
  }

  private def loadCertificateChain(file: File): IO[Array[X509Certificate]] = {
    Resource
      .fromAutoCloseable(IO(new FileInputStream(file.toJava)))
      .use { certificateChainFile =>
        IO {
          val certFactory = CertificateFactory.getInstance("X.509")
          certFactory.generateCertificates(certificateChainFile).asScala.map {
            case cc: X509Certificate => cc
            case _ => throw MalformedSslSettings(s"Certificate chain in ${file.show} contains invalid X509 certificate")
          }.toArray[X509Certificate]
        }
      }
  }

  private def getPrivateKeyAndCertificateChainFromKeystore(keystoreBasedSettings: KeystoreBasedSettings): IO[(PrivateKey, Array[X509Certificate])] = {
    loadKeystore(keystoreBasedSettings, fipsCompliant = false)
      .map { keystore =>
        val alias = prepareAlias(keystore, keystoreBasedSettings)
        val privateKey = keystore.getKey(alias, keystoreBasedSettings.keyPass.map(_.value.toCharArray).orNull) match {
          case pk: PrivateKey => pk
          case _ => throw MalformedSslSettings(s"Configured key with alias=${alias.show} is not a private key")
        }
        val certificateChain = keystore.getCertificateChain(alias).map {
          case cc: X509Certificate => cc
          case _ => throw MalformedSslSettings(s"Certificate chain for alias=${alias.show} is not X509 certificate")
        }
        (privateKey, certificateChain)
      }
  }

  private def trySetProtocolsAndCiphersInsideNewEngine(sslContextBuilder: SslContextBuilder,
                                                       sslSettings: SslSettings) = Try {
    val sslEngine = sslContextBuilder.build().newEngine(ByteBufAllocator.DEFAULT)
    logger.info(s"ROR SSL: Available ciphers: ${sslEngine.getEnabledCipherSuites.toList.show}")
    if (sslSettings.allowedCiphers.nonEmpty) {
      sslEngine.setEnabledCipherSuites(sslSettings.allowedCiphers.map(_.value).toArray)
      logger.info(s"ROR SSL: Restricting to ciphers: ${sslEngine.getEnabledCipherSuites.toList.show}")
    }
    logger.info(s"ROR SSL: Available SSL protocols: ${sslEngine.getEnabledProtocols.toList.show}")
    if (sslSettings.allowedProtocols.nonEmpty) {
      sslEngine.setEnabledProtocols(sslSettings.allowedProtocols.map(_.value).toArray)
      logger.info(s"ROR SSL: Restricting to SSL protocols: ${sslEngine.getEnabledProtocols.toList.show}")
    }
  }

  private def prepareSslContextBuilder(sslSettings: SslSettings): IO[SslContextBuilder] = {
    if (sslSettings.fipsMode.isSslFipsCompliant) {
      val keystoreBasedSettings = sslSettings.serverCertificateSettings match {
        case keystoreBasedSettings: KeystoreBasedSettings => keystoreBasedSettings
        case _ => throw new Exception("KeyStore based settings is required in FIPS compliant mode")
      }
      getFipsCompliantKeyManagerFactory(keystoreBasedSettings)
        .map { keyManagerFactory =>
          logger.info(s"Initializing ROR SSL using SSL provider: ${keyManagerFactory.getProvider.getName.show}")
          SslContextBuilder.forServer(keyManagerFactory)
        }
    } else {
      (sslSettings.serverCertificateSettings match {
        case fileBasedSettings: ServerCertificateSettings.FileBasedSettings =>
          getPrivateKeyAndCertificateChainFromPemFiles(fileBasedSettings)
        case keystoreBasedSettings: KeystoreBasedSettings =>
          getPrivateKeyAndCertificateChainFromKeystore(keystoreBasedSettings)
      }).map { case (privateKey, certificateChain) =>
        logger.info(s"Initializing ROR SSL using default SSL provider ${SslContext.defaultServerProvider().name().show}")
        SslContextBuilder.forServer(privateKey, certificateChain.toList.asJava)
      }
    }
  }

  final case class HostAndPort(host: String, port: Int)

  private implicit class EnableHostnameVerification(val sslEngine: SSLEngine) extends AnyVal {

    def enableHostnameVerification: SSLEngine = {
      val sslParameters = sslEngine.getSSLParameters
      sslParameters.setEndpointIdentificationAlgorithm("HTTPS")
      sslEngine.setSSLParameters(sslParameters)
      sslEngine
    }
  }

  final case class UnableToLoadDataFromProvidedFilesException(cause: Throwable)
    extends Exception(s"Unable to load data from provided files", cause)
  final case class UnableToInitializeSslContextBuilderUsingProvidedFiles(cause: Throwable)
    extends Exception(s"Unable to initialize Key Manager Factory using provided keystore.", cause)
  final case class UnableToInitializeTrustManagerFactoryUsingProvidedTruststore(cause: Throwable)
    extends Exception(s"Unable to initialize Trust Manager Factory using provided truststore.", cause)
  final case class MalformedSslSettings(str: String) extends Exception(str)
  case object TrustManagerNotConfiguredException extends Exception("Trust manager has not been configured!")

  private implicit def optionalKeystorePasswordToCharArray(keystorePassword: Option[KeystorePassword]): Array[Char] = {
    keystorePassword.map(_.value.toCharArray).orNull
  }

  private implicit def optionalTruststorePasswordToCharArray(truststorePassword: Option[TruststorePassword]): Array[Char] = {
    truststorePassword.map(_.value.toCharArray).orNull
  }

}
