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
package tech.beshu.ror.settings.es

import better.files.*
import cats.data.{EitherT, NonEmptyList}
import io.circe.{Decoder, DecodingFailure, HCursor}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.{EsConfigFile, RorSettingsFile}
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.SslSettings.*
import tech.beshu.ror.utils.SSLCertHelper
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

sealed trait RorSslSettings
object RorSslSettings extends YamlFileBasedSettingsLoaderSupport with Logging {

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

  def load(esEnv: EsEnv,
           rorSettingsFile: RorSettingsFile)
          (implicit systemContext: SystemContext): Task[Either[MalformedSettings, Option[RorSslSettings]]] = {
    val result = for {
      xpackSecuritySettings <- loadXpackSecuritySettings(esEnv)
      rorSslSettings <- loadRorSslSetting(esEnv.elasticsearchConfig, rorSettingsFile, xpackSecuritySettings)
    } yield rorSslSettings
    result.value
  }

  private def loadXpackSecuritySettings(esEnv: EsEnv)
                                       (implicit systemContext: SystemContext): EitherT[Task, MalformedSettings, XpackSecuritySettings] = {
    EitherT {
      implicit val decoder: Decoder[XpackSecuritySettings] = xpackSettingsDecoder(esEnv.isOssDistribution)
      loadSetting[XpackSecuritySettings](esEnv, "X-Pack settings")
    }
  }

  private def loadRorSslSetting(esConfigFile: EsConfigFile,
                                rorSettingsFile: RorSettingsFile,
                                xpackSecuritySettings: XpackSecuritySettings)
                               (implicit systemContext: SystemContext): EitherT[Task, MalformedSettings, Option[RorSslSettings]] = {
    implicit val rorSslSettingsDecoder: Decoder[Option[RorSslSettings]] = SslDecoders.rorSslDecoder(esConfigFile.file.parent)
    loadSslSettingsFrom(esConfigFile.file)
      .flatMap {
        case None =>
          fallbackToRorSettingsFile(rorSettingsFile)
        case Some(ssl) =>
          EitherT.rightT(Some(ssl))
      }
      .subflatMap {
        case Some(ssl) if xpackSecuritySettings.enabled =>
          Left(MalformedSettings("Cannot use ROR SSL when XPack Security is enabled"))
        case rorSsl@(Some(_) | None) =>
          Right(rorSsl)
      }
  }

  private def fallbackToRorSettingsFile(rorSettingsFile: RorSettingsFile)
                                       (implicit decoder: Decoder[Option[RorSslSettings]],
                                        systemContext: SystemContext): EitherT[Task, MalformedSettings, Option[RorSslSettings]] = {
    val settingsFile = rorSettingsFile.file
    if (settingsFile.exists) {
      for {
        _ <- lift(logger.warn(s"Defining SSL settings in ReadonlyREST file is deprecated and will be removed in the future. Move your ReadonlyREST SSL settings to Elasticsearch config file. See https://docs.readonlyrest.com/elasticsearch#encryption for details"))
        settings <- loadSslSettingsFrom(settingsFile)
      } yield settings
    } else {
      EitherT.rightT(None)
    }
  }

  private def loadSslSettingsFrom(settingsFile: File)
                                 (implicit decoder: Decoder[Option[RorSslSettings]],
                                  systemContext: SystemContext) = {
    for {
      _ <- lift(logger.info(s"Trying to load ROR SSL settings from '${settingsFile.show}' file ..."))
      settings <- EitherT(loadSetting[Option[RorSslSettings]](settingsFile, "ROR SSL settings"))
      _ <- lift(logger.info(settings match {
        case Some(_) => s"ROR SSL settings loaded from '${settingsFile.show}' file."
        case None => s"No ROR SSL settings found in '${settingsFile.show}' file."
      }))
    } yield settings
  }

  private final case class XpackSecuritySettings(enabled: Boolean)

  private def xpackSettingsDecoder(isOssDistribution: Boolean): Decoder[XpackSecuritySettings] = {
    if (isOssDistribution) {
      Decoder.const(XpackSecuritySettings(enabled = false))
    } else {
      val booleanDecoder = YamlKeyDecoder[Boolean](
        path = NonEmptyList.of("xpack", "security", "enabled"),
        default = true
      )
      val stringDecoder = YamlKeyDecoder[String](
        path = NonEmptyList.of("xpack", "security", "enabled"),
        default = "true"
      ) map {
        _.toBoolean
      }
      (booleanDecoder or stringDecoder) map XpackSecuritySettings.apply
    }
  }

  private def lift[T](value: => T): EitherT[Task, MalformedSettings, T] = EitherT.rightT(value)
}

sealed trait SslSettings {
  def serverCertificateSettings: SslSettings.ServerCertificateSettings

  def clientCertificateSettings: Option[SslSettings.ClientCertificateSettings]

  def allowedProtocols: Set[SslSettings.Protocol]

  def allowedCiphers: Set[SslSettings.Cipher]

  def clientAuthenticationEnabled: Boolean

  def certificateVerificationEnabled: Boolean

  def fipsMode: FipsMode
}

object SslSettings {

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

  sealed trait ServerCertificateSettings
  object ServerCertificateSettings {
    final case class KeystoreBasedSettings(keystoreFile: KeystoreFile,
                                           keystorePassword: Option[KeystorePassword],
                                           keyAlias: Option[KeyAlias],
                                           keyPass: Option[KeyPass])
      extends ServerCertificateSettings
    final case class FileBasedSettings(serverCertificateKeyFile: ServerCertificateKeyFile,
                                       serverCertificateFile: ServerCertificateFile)
      extends ServerCertificateSettings
  }

  sealed trait ClientCertificateSettings
  object ClientCertificateSettings {
    final case class TruststoreBasedSettings(truststoreFile: TruststoreFile,
                                             truststorePassword: Option[TruststorePassword])
      extends ClientCertificateSettings
    final case class FileBasedSettings(clientTrustedCertificateFile: ClientTrustedCertificateFile)
      extends ClientCertificateSettings
  }

  final case class ExternalSslSettings(serverCertificateSettings: ServerCertificateSettings,
                                       clientCertificateSettings: Option[ClientCertificateSettings],
                                       allowedProtocols: Set[SslSettings.Protocol],
                                       allowedCiphers: Set[SslSettings.Cipher],
                                       clientAuthenticationEnabled: Boolean,
                                       fipsMode: FipsMode)
    extends SslSettings {

    val certificateVerificationEnabled: Boolean = false
  }

  final case class InternodeSslSettings(serverCertificateSettings: ServerCertificateSettings,
                                        clientCertificateSettings: Option[ClientCertificateSettings],
                                        allowedProtocols: Set[SslSettings.Protocol],
                                        allowedCiphers: Set[SslSettings.Cipher],
                                        clientAuthenticationEnabled: Boolean,
                                        certificateVerificationEnabled: Boolean,
                                        hostnameVerificationEnabled: Boolean,
                                        fipsMode: FipsMode)
    extends SslSettings

  sealed trait FipsMode
  object FipsMode {
    case object NonFips extends FipsMode
    case object SslOnly extends FipsMode
  }

}

private object SslDecoders extends Logging {

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

  final case class CommonSslProperties(serverCertificateSettings: ServerCertificateSettings,
                                       clientCertificateSettings: Option[ClientCertificateSettings],
                                       allowedProtocols: Set[SslSettings.Protocol],
                                       allowedCiphers: Set[SslSettings.Cipher],
                                       clientAuthentication: Option[Boolean])

  private implicit val keystorePasswordDecoder: Decoder[KeystorePassword] = DecoderHelpers.decodeStringLike.map(KeystorePassword.apply)
  private implicit val truststorePasswordDecoder: Decoder[TruststorePassword] = DecoderHelpers.decodeStringLike.map(TruststorePassword.apply)
  private implicit val keyPassDecoder: Decoder[KeyPass] = DecoderHelpers.decodeStringLike.map(KeyPass.apply)
  private implicit val keyAliasDecoder: Decoder[KeyAlias] = DecoderHelpers.decodeStringLike.map(KeyAlias.apply)
  private implicit val cipherDecoder: Decoder[Cipher] = DecoderHelpers.decodeStringLike.map(Cipher.apply)
  private implicit val protocolDecoder: Decoder[Protocol] = DecoderHelpers.decodeStringLike.map(Protocol.apply)

  private def clientCertificateSettingsDecoder(basePath: File): Decoder[Option[ClientCertificateSettings]] = {
    val aFileDecoder: Decoder[File] = fileDecoder(basePath)
    implicit val truststoreFileDecoder = aFileDecoder.map(TruststoreFile.apply)
    implicit val clientTrustedCertificateFileDecoder = aFileDecoder.map(ClientTrustedCertificateFile.apply)

    val truststoreBasedClientCertificateSettingsDecoder: Decoder[ClientCertificateSettings] =
      Decoder.forProduct2(consts.truststoreFile, consts.truststorePass)(ClientCertificateSettings.TruststoreBasedSettings.apply)
    val fileBasedClientCertificateSettingsDecoder: Decoder[ClientCertificateSettings] =
      Decoder.forProduct1(consts.clientTrustedCertificateFile)(ClientCertificateSettings.FileBasedSettings.apply)
    Decoder.instance { c =>
      val truststoreBasedKeys = Set(consts.truststoreFile, consts.truststorePass)
      val fileBasedKeys = Set(consts.clientTrustedCertificateFile)
      val presentKeys = c.keys.fold[Set[String]](Set.empty)(_.toSet)
      if (presentKeys.intersect(truststoreBasedKeys).nonEmpty && presentKeys.intersect(fileBasedKeys).nonEmpty) {
        val errorMessage = s"Field sets [${fileBasedKeys.show}] and [${truststoreBasedKeys.show}] could not be present in the same settings section"
        logger.error(errorMessage)
        Left(DecodingFailure(errorMessage, List.empty))
      } else if (presentKeys.intersect(truststoreBasedKeys).nonEmpty) {
        truststoreBasedClientCertificateSettingsDecoder(c)
          .map(Option.apply)
      } else if (presentKeys.intersect(fileBasedKeys).nonEmpty) {
        if (SSLCertHelper.isPEMHandlingAvailable) {
          fileBasedClientCertificateSettingsDecoder(c)
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

  private def serverCertificateSettingsDecoder(basePath: File): Decoder[ServerCertificateSettings] = {
    val aFileDecoder: Decoder[File] = fileDecoder(basePath)
    implicit val keystoreFileDecoder = aFileDecoder.map(KeystoreFile.apply)
    implicit val serverCertificateFileDecoder = aFileDecoder.map(ServerCertificateFile.apply)
    implicit val serverCertificateKeyFileDecoder = aFileDecoder.map(ServerCertificateKeyFile.apply)
    val keystoreBasedServerCertificateSettingsDecoder: Decoder[ServerCertificateSettings] =
      Decoder.forProduct4(consts.keystoreFile, consts.keystorePass, consts.keyAlias, consts.keyPass)(ServerCertificateSettings.KeystoreBasedSettings.apply)
    val fileBasedServerCertificateSettingsDecoder: Decoder[ServerCertificateSettings] =
      Decoder.forProduct2(consts.serverCertificateKeyFile, consts.serverCertificateFile)(ServerCertificateSettings.FileBasedSettings.apply)
    Decoder.instance { c =>
      val keystoreBasedKeys = Set(consts.keystoreFile, consts.keystorePass, consts.keyPass, consts.keyAlias)
      val fileBasedKeys = Set(consts.serverCertificateKeyFile, consts.serverCertificateFile)
      val presentKeys = c.keys.fold[Set[String]](Set.empty)(_.toSet)
      if (presentKeys.intersect(keystoreBasedKeys).nonEmpty && presentKeys.intersect(fileBasedKeys).nonEmpty) {
        val errorMessage = s"Field sets [${fileBasedKeys.show}] and [${keystoreBasedKeys.show}] could not be present in the same settings section"
        logger.error(errorMessage)
        Left(DecodingFailure(errorMessage, List.empty))
      } else if (presentKeys.intersect(keystoreBasedKeys).nonEmpty) {
        keystoreBasedServerCertificateSettingsDecoder(c)
      } else if (presentKeys.intersect(fileBasedKeys).nonEmpty) {
        if (SSLCertHelper.isPEMHandlingAvailable) {
          fileBasedServerCertificateSettingsDecoder(c)
        } else {
          val errorMessage = "PEM File Handling is not available in your current deployment of Elasticsearch"
          logger.error(errorMessage)
          Left(DecodingFailure(errorMessage, List.empty))
        }
      } else {
        val errorMessage = "There was no SSL settings present for server"
        logger.error(errorMessage)
        Left(DecodingFailure(errorMessage, List.empty))
      }
    }
  }

  def rorSslDecoder(basePath: File): Decoder[Option[RorSslSettings]] = Decoder.instance { c =>
    implicit val isFipsCompliantDecoder: Decoder[FipsMode] = Decoder.decodeString.emap {
      case "NON_FIPS" => Right(FipsMode.NonFips)
      case "SSL_ONLY" => Right(FipsMode.SslOnly)
      case _ => Left("Invalid settings option for FIPS MODE. Valid values are: NON_FIPS, SSL_ONLY")
    }
    for {
      fipsMode <- c.downField(consts.rorSection).downField(consts.fipsMode).as[Option[FipsMode]]
      interNodeSsl <- {
        implicit val internodeSslSettingsDecoder: Decoder[Option[InternodeSslSettings]] =
          sslInternodeSettingsDecoder(basePath, fipsMode.getOrElse(FipsMode.NonFips))
        c.downField(consts.rorSection).downField(consts.internodeSsl).as[Option[Option[InternodeSslSettings]]]
      }
      externalSsl <- {
        implicit val externalSslSettingsDecoder: Decoder[Option[ExternalSslSettings]] =
          sslExternalSettingsDecoder(basePath, fipsMode.getOrElse(FipsMode.NonFips))
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

  private def sslInternodeSettingsDecoder(basePath: File,
                                          fipsMode: FipsMode): Decoder[Option[InternodeSslSettings]] = Decoder.instance { c =>
    whenEnabled(c) {
      for {
        certificateVerification <- c.downField(consts.certificateVerification).as[Option[Boolean]]
        hostnameVerification <- c.downField(consts.hostnameVerification).as[Option[Boolean]]
        verification <- c.downField(consts.verification).as[Option[Boolean]]
        sslCommonProperties <- sslCommonPropertiesDecoder(basePath, c)
      } yield
        InternodeSslSettings(
          serverCertificateSettings = sslCommonProperties.serverCertificateSettings,
          clientCertificateSettings = sslCommonProperties.clientCertificateSettings,
          allowedProtocols = sslCommonProperties.allowedProtocols,
          allowedCiphers = sslCommonProperties.allowedCiphers,
          clientAuthenticationEnabled = sslCommonProperties.clientAuthentication.getOrElse(false),
          certificateVerificationEnabled = certificateVerification.orElse(verification).getOrElse(false),
          hostnameVerificationEnabled = hostnameVerification.getOrElse(false),
          fipsMode = fipsMode
        )
    }
  }

  private def sslExternalSettingsDecoder(basePath: File,
                                         fipsMode: FipsMode): Decoder[Option[ExternalSslSettings]] = Decoder.instance { c =>
    whenEnabled(c) {
      for {
        verification <- c.downField(consts.verification).as[Option[Boolean]]
        sslCommonProperties <- sslCommonPropertiesDecoder(basePath, c)
      } yield
        ExternalSslSettings(
          serverCertificateSettings = sslCommonProperties.serverCertificateSettings,
          clientCertificateSettings = sslCommonProperties.clientCertificateSettings,
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
      serverCertificateSettings <- serverCertificateSettingsDecoder(basePath).apply(c)
      clientCertificateSettings <- clientCertificateSettingsDecoder(basePath).apply(c)
    } yield
      CommonSslProperties(
        serverCertificateSettings = serverCertificateSettings,
        clientCertificateSettings = clientCertificateSettings,
        allowedProtocols = protocols.getOrElse(Set.empty[Protocol]),
        allowedCiphers = ciphers.getOrElse(Set.empty[Cipher]),
        clientAuthentication = clientAuthentication,
      )
  }

  private def whenEnabled[T <: SslSettings](cursor: HCursor)(decoding: => Either[DecodingFailure, T]) = {
    for {
      isEnabled <- cursor.downField(consts.enable).as[Option[Boolean]]
      result <- if (isEnabled.getOrElse(true)) decoding.map(Some.apply) else Right(None)
    } yield result
  }

  private def fileDecoder(basePath: File): Decoder[File] =
    Decoder.decodeString.map { str => basePath / str }
}
