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
package tech.beshu.ror.configuration

import better.files.*
import io.circe.{Decoder, DecodingFailure, HCursor}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.RorSettingsFile
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.configuration.SslConfiguration.{ExternalSslSettings, FipsMode, InternodeSslSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.SSLCertHelper

sealed trait RorSslSettings
object RorSslSettings extends Logging {

  final case class OnlyExternalSslSettings(ssl: ExternalSslSettings) extends RorSslSettings
  final case class OnlyInternodeSslSettings(ssl: InternodeSslSettings) extends RorSslSettings
  final case class ExternalAndInternodeSslSettings(external: ExternalSslSettings,
                                                   internode: InternodeSslSettings)
    extends RorSslSettings

  implicit class ExtractSsl(val rorSsl: RorSslSettings) extends AnyVal {
    def externalSsl: Option[ExternalSslSettings] = rorSsl match {
      case OnlyExternalSslSettings(ssl) => Some(ssl)
      case OnlyInternodeSslSettings(_) => None
      case ExternalAndInternodeSslSettings(ssl, _) => Some(ssl)
    }

    def internodeSsl: Option[InternodeSslSettings] = rorSsl match {
      case OnlyExternalSslSettings(_) => None
      case OnlyInternodeSslSettings(ssl) => Some(ssl)
      case ExternalAndInternodeSslSettings(_, ssl) => Some(ssl)
    }
  }

  implicit class IsSslFipsCompliant(val fipsMode: FipsMode) extends AnyVal {
    def isSslFipsCompliant: Boolean = fipsMode match {
      case FipsMode.NonFips => false
      case FipsMode.SslOnly => true
    }
  }

  def load(rorSettingsFile: RorSettingsFile, esConfigFile: File)
          (implicit systemContext: SystemContext): Task[Either[MalformedSettings, Option[RorSslSettings]]] = Task {
    implicit val rorSslSettingsDecoder: Decoder[Option[RorSslSettings]] = SslDecoders.rorSslDecoder(esConfigFile.parent)
    loadSslSettingsFrom(esConfigFile)
      .fold(
        error => Left(error),
        {
          case None =>
            logger.info(s"Cannot find ROR SSL settings in ${esConfigFile.show} ...")
            fallbackToRorSettingsFile(rorSettingsFile)
          case Some(ssl) =>
            Right(Some(ssl))
        }
      )
  }

  private def fallbackToRorSettingsFile(rorSettingsFile: RorSettingsFile)
                                       (implicit decoder: Decoder[Option[RorSslSettings]],
                                        systemContext: SystemContext) = {
    val settingsFile = rorSettingsFile.file
    logger.info(s"... trying: ${settingsFile.show}")
    if (settingsFile.exists) {
      loadSslSettingsFrom(settingsFile)
    } else {
      Right(None)
    }
  }

  private def loadSslSettingsFrom(settingsFile: File)
                                 (implicit decoder: Decoder[Option[RorSslSettings]],
                                  systemContext: SystemContext) = {
    new YamlFileBasedSettingsLoader(settingsFile)
      .loadSettings[Option[RorSslSettings]](settingsName = "ROR SSL settings")
  }
}

sealed trait SslConfiguration {
  def serverCertificateConfiguration: SslConfiguration.ServerCertificateConfiguration

  def clientCertificateConfiguration: Option[SslConfiguration.ClientCertificateConfiguration]

  def allowedProtocols: Set[SslConfiguration.Protocol]

  def allowedCiphers: Set[SslConfiguration.Cipher]

  def clientAuthenticationEnabled: Boolean

  def certificateVerificationEnabled: Boolean

  def fipsMode: FipsMode
}

object SslConfiguration {

  final case class KeystorePassword(value: String)
  final case class KeystoreFile(value: File)
  final case class TruststorePassword(value: String)
  final case class TruststoreFile(value: File)
  final case class ServerCertificateKeyFile(value: File)
  final case class ServerCertificateFile(value: File)
  final case class ClientTrustedCertificateFile(value: File)
  final case class KeyPass(value: String)
  final case class KeyAlias(value: String)
  final case class Cipher(value: String)
  final case class Protocol(value: String)

  sealed trait ServerCertificateConfiguration
  object ServerCertificateConfiguration {
    final case class KeystoreBasedConfiguration(keystoreFile: KeystoreFile,
                                                keystorePassword: Option[KeystorePassword],
                                                keyAlias: Option[KeyAlias],
                                                keyPass: Option[KeyPass]) extends ServerCertificateConfiguration
    final case class FileBasedConfiguration(serverCertificateKeyFile: ServerCertificateKeyFile,
                                            serverCertificateFile: ServerCertificateFile) extends ServerCertificateConfiguration
  }

  sealed trait ClientCertificateConfiguration
  object ClientCertificateConfiguration {
    final case class TruststoreBasedConfiguration(truststoreFile: TruststoreFile,
                                                  truststorePassword: Option[TruststorePassword]) extends ClientCertificateConfiguration
    final case class FileBasedConfiguration(clientTrustedCertificateFile: ClientTrustedCertificateFile) extends ClientCertificateConfiguration
  }

  final case class ExternalSslSettings(serverCertificateConfiguration: ServerCertificateConfiguration,
                                       clientCertificateConfiguration: Option[ClientCertificateConfiguration],
                                       allowedProtocols: Set[SslConfiguration.Protocol],
                                       allowedCiphers: Set[SslConfiguration.Cipher],
                                       clientAuthenticationEnabled: Boolean,
                                       fipsMode: FipsMode)
    extends SslConfiguration {

    val certificateVerificationEnabled: Boolean = false
  }

  final case class InternodeSslSettings(serverCertificateConfiguration: ServerCertificateConfiguration,
                                        clientCertificateConfiguration: Option[ClientCertificateConfiguration],
                                        allowedProtocols: Set[SslConfiguration.Protocol],
                                        allowedCiphers: Set[SslConfiguration.Cipher],
                                        clientAuthenticationEnabled: Boolean,
                                        certificateVerificationEnabled: Boolean,
                                        hostnameVerificationEnabled: Boolean,
                                        fipsMode: FipsMode)
    extends SslConfiguration

  sealed trait FipsMode
  object FipsMode {
    case object NonFips extends FipsMode
    case object SslOnly extends FipsMode
  }

}

private object SslDecoders extends Logging {

  import tech.beshu.ror.configuration.SslConfiguration.*

  object consts {
    val rorSection = "readonlyrest"
    val fipsMode = "fips_mode"
    val externalSsl = "ssl"
    val internodeSsl = "ssl_internode"
    val keystoreFile = "keystore_file"
    val keystorePass = "keystore_pass"
    val truststoreFile = "truststore_file"
    val truststorePass = "truststore_pass"
    val keyPass = "key_pass"
    val keyAlias = "key_alias"
    val allowedCiphers = "allowed_ciphers"
    val allowedProtocols = "allowed_protocols"
    val certificateVerification = "certificate_verification"
    val hostnameVerification = "hostname_verification"
    val clientAuthentication = "client_authentication"
    val verification = "verification"
    val enable = "enable"
    val serverCertificateKeyFile = "server_certificate_key_file"
    val serverCertificateFile = "server_certificate_file"
    val clientTrustedCertificateFile = "client_trusted_certificate_file"
  }

  final case class CommonSslProperties(serverCertificateConfiguration: ServerCertificateConfiguration,
                                       clientCertificateConfiguration: Option[ClientCertificateConfiguration],
                                       allowedProtocols: Set[SslConfiguration.Protocol],
                                       allowedCiphers: Set[SslConfiguration.Cipher],
                                       clientAuthentication: Option[Boolean])

  private implicit val keystorePasswordDecoder: Decoder[KeystorePassword] = DecoderHelpers.decodeStringLike.map(KeystorePassword.apply)
  private implicit val truststorePasswordDecoder: Decoder[TruststorePassword] = DecoderHelpers.decodeStringLike.map(TruststorePassword.apply)
  private implicit val keyPassDecoder: Decoder[KeyPass] = DecoderHelpers.decodeStringLike.map(KeyPass.apply)
  private implicit val keyAliasDecoder: Decoder[KeyAlias] = DecoderHelpers.decodeStringLike.map(KeyAlias.apply)
  private implicit val cipherDecoder: Decoder[Cipher] = DecoderHelpers.decodeStringLike.map(Cipher.apply)
  private implicit val protocolDecoder: Decoder[Protocol] = DecoderHelpers.decodeStringLike.map(Protocol.apply)

  private def clientCertificateConfigurationDecoder(basePath: File): Decoder[Option[ClientCertificateConfiguration]] = {
    val aFileDecoder: Decoder[File] = fileDecoder(basePath)
    implicit val truststoreFileDecoder = aFileDecoder.map(TruststoreFile.apply)
    implicit val clientTrustedCertificateFileDecoder = aFileDecoder.map(ClientTrustedCertificateFile.apply)

    val truststoreBasedClientCertificateConfigurationDecoder: Decoder[ClientCertificateConfiguration] =
      Decoder.forProduct2(consts.truststoreFile, consts.truststorePass)(ClientCertificateConfiguration.TruststoreBasedConfiguration.apply)
    val fileBasedClientCertificateConfigurationDecoder: Decoder[ClientCertificateConfiguration] =
      Decoder.forProduct1(consts.clientTrustedCertificateFile)(ClientCertificateConfiguration.FileBasedConfiguration.apply)
    Decoder.instance { c =>
      val truststoreBasedKeys = Set(consts.truststoreFile, consts.truststorePass)
      val fileBasedKeys = Set(consts.clientTrustedCertificateFile)
      val presentKeys = c.keys.fold[Set[String]](Set.empty)(_.toSet)
      if (presentKeys.intersect(truststoreBasedKeys).nonEmpty && presentKeys.intersect(fileBasedKeys).nonEmpty) {
        val errorMessage = s"Field sets [${fileBasedKeys.show}] and [${truststoreBasedKeys.show}] could not be present in the same configuration section"
        logger.error(errorMessage)
        Left(DecodingFailure(errorMessage, List.empty))
      } else if (presentKeys.intersect(truststoreBasedKeys).nonEmpty) {
        truststoreBasedClientCertificateConfigurationDecoder(c)
          .map(Option.apply)
      } else if (presentKeys.intersect(fileBasedKeys).nonEmpty) {
        if (SSLCertHelper.isPEMHandlingAvailable) {
          fileBasedClientCertificateConfigurationDecoder(c)
            .map(Option.apply)
        } else {
          val errorMessage = "PEM File Handling is not available in your current deployment of Elasticsearch"
          logger.error(errorMessage)
          Left(DecodingFailure(errorMessage, List.empty))
        }
      } else {
        Right(None)
      }
    }
  }

  private def serverCertificateConfigurationDecoder(basePath: File): Decoder[ServerCertificateConfiguration] = {
    val aFileDecoder: Decoder[File] = fileDecoder(basePath)
    implicit val keystoreFileDecoder = aFileDecoder.map(KeystoreFile.apply)
    implicit val serverCertificateFileDecoder = aFileDecoder.map(ServerCertificateFile.apply)
    implicit val serverCertificateKeyFileDecoder = aFileDecoder.map(ServerCertificateKeyFile.apply)
    val keystoreBasedServerCertificateConfigurationDecoder: Decoder[ServerCertificateConfiguration] =
      Decoder.forProduct4(consts.keystoreFile, consts.keystorePass, consts.keyAlias, consts.keyPass)(ServerCertificateConfiguration.KeystoreBasedConfiguration.apply)
    val fileBasedServerCertificateConfigurationDecoder: Decoder[ServerCertificateConfiguration] =
      Decoder.forProduct2(consts.serverCertificateKeyFile, consts.serverCertificateFile)(ServerCertificateConfiguration.FileBasedConfiguration.apply)
    Decoder.instance { c =>
      val keystoreBasedKeys = Set(consts.keystoreFile, consts.keystorePass, consts.keyPass, consts.keyAlias)
      val fileBasedKeys = Set(consts.serverCertificateKeyFile, consts.serverCertificateFile)
      val presentKeys = c.keys.fold[Set[String]](Set.empty)(_.toSet)
      if (presentKeys.intersect(keystoreBasedKeys).nonEmpty && presentKeys.intersect(fileBasedKeys).nonEmpty) {
        val errorMessage = s"Field sets [${fileBasedKeys.show}] and [${keystoreBasedKeys.show}] could not be present in the same configuration section"
        logger.error(errorMessage)
        Left(DecodingFailure(errorMessage, List.empty))
      } else if (presentKeys.intersect(keystoreBasedKeys).nonEmpty) {
        keystoreBasedServerCertificateConfigurationDecoder(c)
      } else if (presentKeys.intersect(fileBasedKeys).nonEmpty) {
        if (SSLCertHelper.isPEMHandlingAvailable) {
          fileBasedServerCertificateConfigurationDecoder(c)
        } else {
          val errorMessage = "PEM File Handling is not available in your current deployment of Elasticsearch"
          logger.error(errorMessage)
          Left(DecodingFailure(errorMessage, List.empty))
        }
      } else {
        val errorMessage = "There was no SSL configuration present for server"
        logger.error(errorMessage)
        Left(DecodingFailure(errorMessage, List.empty))
      }
    }
  }

  def rorSslDecoder(basePath: File): Decoder[Option[RorSslSettings]] = Decoder.instance { c =>
    implicit val isFipsCompliantDecoder: Decoder[FipsMode] = Decoder.decodeString.emap {
      case "NON_FIPS" => Right(FipsMode.NonFips)
      case "SSL_ONLY" => Right(FipsMode.SslOnly)
      case _ => Left("Invalid configuration option for FIPS MODE. Valid values are: NON_FIPS, SSL_ONLY")
    }
    for {
      fipsMode <- c.downField(consts.rorSection).downField(consts.fipsMode).as[Option[FipsMode]]
      interNodeSsl <- {
        implicit val internodeSslConfigDecoder = sslInternodeConfigurationDecoder(basePath, fipsMode.getOrElse(FipsMode.NonFips))
        c.downField(consts.rorSection).downField(consts.internodeSsl).as[Option[Option[InternodeSslSettings]]]
      }
      externalSsl <- {
        implicit val externalSslConfigDecoder = sslExternalConfigurationDecoder(basePath, fipsMode.getOrElse(FipsMode.NonFips))
        c.downField(consts.rorSection).downField(consts.externalSsl).as[Option[Option[ExternalSslSettings]]]
      }
    } yield {
      (externalSsl.flatten, interNodeSsl.flatten) match {
        case (Some(ssl), None) => Some(RorSslSettings.OnlyExternalSslSettings(ssl))
        case (None, Some(ssl)) => Some(RorSslSettings.OnlyInternodeSslSettings(ssl))
        case (Some(externalSsl), Some(internalSsl)) => Some(RorSslSettings.ExternalAndInternodeSslSettings(externalSsl, internalSsl))
        case (None, None) => None
      }
    }
  }

  private def sslInternodeConfigurationDecoder(basePath: File,
                                               fipsMode: FipsMode): Decoder[Option[InternodeSslSettings]] = Decoder.instance { c =>
    whenEnabled(c) {
      for {
        certificateVerification <- c.downField(consts.certificateVerification).as[Option[Boolean]]
        hostnameVerification <- c.downField(consts.hostnameVerification).as[Option[Boolean]]
        verification <- c.downField(consts.verification).as[Option[Boolean]]
        sslCommonProperties <- sslCommonPropertiesDecoder(basePath, c)
      } yield
        InternodeSslSettings(
          serverCertificateConfiguration = sslCommonProperties.serverCertificateConfiguration,
          clientCertificateConfiguration = sslCommonProperties.clientCertificateConfiguration,
          allowedProtocols = sslCommonProperties.allowedProtocols,
          allowedCiphers = sslCommonProperties.allowedCiphers,
          clientAuthenticationEnabled = sslCommonProperties.clientAuthentication.getOrElse(false),
          certificateVerificationEnabled = certificateVerification.orElse(verification).getOrElse(false),
          hostnameVerificationEnabled = hostnameVerification.getOrElse(false),
          fipsMode = fipsMode
        )
    }
  }

  private def sslExternalConfigurationDecoder(basePath: File,
                                              fipsMode: FipsMode): Decoder[Option[ExternalSslSettings]] = Decoder.instance { c =>
    whenEnabled(c) {
      for {
        verification <- c.downField(consts.verification).as[Option[Boolean]]
        sslCommonProperties <- sslCommonPropertiesDecoder(basePath, c)
      } yield
        ExternalSslSettings(
          serverCertificateConfiguration = sslCommonProperties.serverCertificateConfiguration,
          clientCertificateConfiguration = sslCommonProperties.clientCertificateConfiguration,
          allowedProtocols = sslCommonProperties.allowedProtocols,
          allowedCiphers = sslCommonProperties.allowedCiphers,
          clientAuthenticationEnabled = sslCommonProperties.clientAuthentication.orElse(verification).getOrElse(false),
          fipsMode = fipsMode
        )
    }
  }

  private def sslCommonPropertiesDecoder(basePath: File, c: HCursor) = {
    for {
      ciphers <- c.downField(consts.allowedCiphers).as[Option[Set[Cipher]]]
      protocols <- c.downField(consts.allowedProtocols).as[Option[Set[Protocol]]]
      clientAuthentication <- c.downField(consts.clientAuthentication).as[Option[Boolean]]
      serverCertificateConfiguration <- serverCertificateConfigurationDecoder(basePath).apply(c)
      clientCertificateConfiguration <- clientCertificateConfigurationDecoder(basePath).apply(c)
    } yield
      CommonSslProperties(
        serverCertificateConfiguration = serverCertificateConfiguration,
        clientCertificateConfiguration = clientCertificateConfiguration,
        allowedProtocols = protocols.getOrElse(Set.empty[Protocol]),
        allowedCiphers = ciphers.getOrElse(Set.empty[Cipher]),
        clientAuthentication = clientAuthentication,
      )
  }

  private def whenEnabled[T <: SslConfiguration](cursor: HCursor)(decoding: => Either[DecodingFailure, T]) = {
    for {
      isEnabled <- cursor.downField(consts.enable).as[Option[Boolean]]
      result <- if (isEnabled.getOrElse(true)) decoding.map(Some.apply) else Right(None)
    } yield result
  }

  private def fileDecoder(basePath: File): Decoder[File] =
    Decoder.decodeString.map { str => basePath / str }
}
