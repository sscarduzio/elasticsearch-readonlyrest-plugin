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

import java.io.{File => JFile}
import java.nio.file.{Path, Paths}
import better.files._
import io.circe.{Decoder, DecodingFailure, HCursor}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.configuration.SslConfiguration.{ExternalSslConfiguration, InternodeSslConfiguration, KeystoreFile, TruststoreFile}
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}

final case class RorSsl(externalSsl: Option[ExternalSslConfiguration],
                        interNodeSsl: Option[InternodeSslConfiguration])

object RorSsl extends Logging {

  val noSsl = RorSsl(None, None)

  def load(esConfigFolderPath: Path)
          (implicit envVarsProvider:EnvVarsProvider,
           propertiesProvider: PropertiesProvider): Task[Either[MalformedSettings, RorSsl]] = Task {
    implicit val sslDecoder: Decoder[RorSsl] = SslDecoders.rorSslDecoder(esConfigFolderPath)
    val esConfig = File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath)
    loadSslConfigFromFile(esConfig)
      .fold(
        error => Left(error),
        {
          case RorSsl(None, None) => fallbackToRorConfig(esConfigFolderPath)
          case ssl => Right(ssl)
        }
      )
  }

  private def fallbackToRorConfig(esConfigFolderPath: Path)
                                 (implicit rorSslDecoder: Decoder[RorSsl],
                                  envVarsProvider: EnvVarsProvider,
                                  propertiesProvider: PropertiesProvider) = {
    val rorConfig = new FileConfigLoader(esConfigFolderPath).rawConfigFile
    logger.info(s"Cannot find SSL configuration in elasticsearch.yml, trying: ${rorConfig.pathAsString}")
    if (rorConfig.exists) {
      loadSslConfigFromFile(rorConfig)
    } else {
      Right(RorSsl.noSsl)
    }
  }

  private def loadSslConfigFromFile(config: File)
                                   (implicit rorSslDecoder: Decoder[RorSsl],
                                    envVarsProvider: EnvVarsProvider) = {
    new EsConfigFileLoader[RorSsl]().loadConfigFromFile(config, "ROR SSL")
  }
}

sealed trait SslConfiguration {
  def keystoreFile: KeystoreFile
  def keystorePassword: Option[SslConfiguration.KeystorePassword]
  def keyPass: Option[SslConfiguration.KeyPass]
  def keyAlias: Option[SslConfiguration.KeyAlias]
  def truststoreFile: Option[TruststoreFile]
  def truststorePassword: Option[SslConfiguration.TruststorePassword]
  def allowedProtocols: Set[SslConfiguration.Protocol]
  def allowedCiphers: Set[SslConfiguration.Cipher]
}

object SslConfiguration {

  final case class KeystorePassword(value: String)
  final case class KeystoreFile(value: JFile)
  final case class TruststorePassword(value: String)
  final case class TruststoreFile(value: JFile)
  final case class KeyPass(value: String)
  final case class KeyAlias(value: String)
  final case class Cipher(value: String)
  final case class Protocol(value: String)

  final case class ExternalSslConfiguration(keystoreFile: KeystoreFile,
                                            keystorePassword: Option[SslConfiguration.KeystorePassword],
                                            keyPass: Option[SslConfiguration.KeyPass],
                                            keyAlias: Option[SslConfiguration.KeyAlias],
                                            truststoreFile: Option[TruststoreFile],
                                            truststorePassword: Option[SslConfiguration.TruststorePassword],
                                            allowedProtocols: Set[SslConfiguration.Protocol],
                                            allowedCiphers: Set[SslConfiguration.Cipher],
                                            clientAuthenticationEnabled: Boolean) extends SslConfiguration

  final case class InternodeSslConfiguration(keystoreFile: KeystoreFile,
                                             keystorePassword: Option[SslConfiguration.KeystorePassword],
                                             keyPass: Option[SslConfiguration.KeyPass],
                                             keyAlias: Option[SslConfiguration.KeyAlias],
                                             truststoreFile: Option[TruststoreFile],
                                             truststorePassword: Option[SslConfiguration.TruststorePassword],
                                             allowedProtocols: Set[SslConfiguration.Protocol],
                                             allowedCiphers: Set[SslConfiguration.Cipher],
                                             certificateVerificationEnabled: Boolean) extends SslConfiguration
}

private object SslDecoders {
  import tech.beshu.ror.configuration.SslConfiguration._

  object consts {
    val rorSection = "readonlyrest"
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
    val clientAuthentication = "client_authentication"
    val verification = "verification"
    val enable = "enable"
  }

  final case class CommonSslProperties(keystoreFile: KeystoreFile,
                                       keystorePassword: Option[SslConfiguration.KeystorePassword],
                                       keyPass: Option[SslConfiguration.KeyPass],
                                       keyAlias: Option[SslConfiguration.KeyAlias],
                                       truststoreFile: Option[TruststoreFile],
                                       truststorePassword: Option[SslConfiguration.TruststorePassword],
                                       allowedProtocols: Set[SslConfiguration.Protocol],
                                       allowedCiphers: Set[SslConfiguration.Cipher],
                                       verification: Option[Boolean])

  private implicit val keystorePasswordDecoder: Decoder[KeystorePassword] = DecoderHelpers.decodeStringLike.map(KeystorePassword.apply)
  private implicit val truststorePasswordDecoder: Decoder[TruststorePassword] = DecoderHelpers.decodeStringLike.map(TruststorePassword.apply)
  private implicit val keyPassDecoder: Decoder[KeyPass] = DecoderHelpers.decodeStringLike.map(KeyPass.apply)
  private implicit val keyAliasDecoder: Decoder[KeyAlias] = DecoderHelpers.decodeStringLike.map(KeyAlias.apply)
  private implicit val cipherDecoder: Decoder[Cipher] = DecoderHelpers.decodeStringLike.map(Cipher.apply)
  private implicit val protocolDecoder: Decoder[Protocol] = DecoderHelpers.decodeStringLike.map(Protocol.apply)

  def rorSslDecoder(basePath: Path): Decoder[RorSsl] = Decoder.instance { c =>
    implicit val internodeSslConfigDecoder: Decoder[Option[InternodeSslConfiguration]] = sslInternodeConfigurationDecoder(basePath)
    implicit val externalSslConfigDecoder: Decoder[Option[ExternalSslConfiguration]] = sslExternalConfigurationDecoder(basePath)
    for {
      interNodeSsl <- c.downField(consts.rorSection).downField(consts.internodeSsl).as[Option[Option[InternodeSslConfiguration]]]
      externalSsl <- c.downField(consts.rorSection).downField(consts.externalSsl).as[Option[Option[ExternalSslConfiguration]]]
    } yield RorSsl(externalSsl.flatten, interNodeSsl.flatten)
  }

  private def sslInternodeConfigurationDecoder(basePath: Path): Decoder[Option[InternodeSslConfiguration]] = Decoder.instance { c =>
    whenEnabled(c) {
      for {
        certificateVerification <- c.downField(consts.certificateVerification).as[Option[Boolean]]
        sslCommonProperties <- sslCommonPropertiesDecoder(basePath, c)
      } yield
        InternodeSslConfiguration(
          keystoreFile = sslCommonProperties.keystoreFile,
          keystorePassword = sslCommonProperties.keystorePassword,
          keyPass = sslCommonProperties.keyPass,
          keyAlias = sslCommonProperties.keyAlias,
          truststoreFile = sslCommonProperties.truststoreFile,
          truststorePassword = sslCommonProperties.truststorePassword,
          allowedProtocols = sslCommonProperties.allowedProtocols,
          allowedCiphers = sslCommonProperties.allowedCiphers,
          certificateVerificationEnabled = certificateVerification.orElse(sslCommonProperties.verification).getOrElse(false)
        )
    }
  }

  private def sslExternalConfigurationDecoder(basePath: Path): Decoder[Option[ExternalSslConfiguration]] = Decoder.instance { c =>
    whenEnabled(c) {
      for {
        clientAuthentication <- c.downField(consts.clientAuthentication).as[Option[Boolean]]
        sslCommonProperties <- sslCommonPropertiesDecoder(basePath, c)
      } yield
        ExternalSslConfiguration(
          keystoreFile = sslCommonProperties.keystoreFile,
          keystorePassword = sslCommonProperties.keystorePassword,
          keyPass = sslCommonProperties.keyPass,
          keyAlias = sslCommonProperties.keyAlias,
          truststoreFile = sslCommonProperties.truststoreFile,
          truststorePassword = sslCommonProperties.truststorePassword,
          allowedProtocols = sslCommonProperties.allowedProtocols,
          allowedCiphers = sslCommonProperties.allowedCiphers,
          clientAuthenticationEnabled = clientAuthentication.orElse(sslCommonProperties.verification).getOrElse(false)
        )
    }
  }

  private def sslCommonPropertiesDecoder(basePath: Path, c: HCursor) = {
    val jFileDecoder: Decoder[JFile] = fileDecoder(basePath)
    implicit val keystoreFileDecoder = jFileDecoder.map(KeystoreFile)
    implicit val truststoreFileDecoder = jFileDecoder.map(TruststoreFile)
    for {
      keystoreFile <- c.downField(consts.keystoreFile).as[KeystoreFile]
      keystorePassword <- c.downField(consts.keystorePass).as[Option[KeystorePassword]]
      truststoreFile <- c.downField(consts.truststoreFile).as[Option[TruststoreFile]]
      truststorePassword <- c.downField(consts.truststorePass).as[Option[TruststorePassword]]
      keyPass <- c.downField(consts.keyPass).as[Option[KeyPass]]
      keyAlias <- c.downField(consts.keyAlias).as[Option[KeyAlias]]
      ciphers <- c.downField(consts.allowedCiphers).as[Option[Set[Cipher]]]
      protocols <- c.downField(consts.allowedProtocols).as[Option[Set[Protocol]]]
      verification <- c.downField(consts.verification).as[Option[Boolean]]
    } yield
      CommonSslProperties(
        keystoreFile = keystoreFile,
        keystorePassword = keystorePassword,
        keyPass = keyPass,
        keyAlias = keyAlias,
        truststoreFile = truststoreFile,
        truststorePassword = truststorePassword,
        allowedProtocols = protocols.getOrElse(Set.empty[Protocol]),
        allowedCiphers = ciphers.getOrElse(Set.empty[Cipher]),
        verification = verification
      )
  }

  private def whenEnabled[T <: SslConfiguration](cursor: HCursor)(decoding: => Either[DecodingFailure, T]) = {
    for {
      isEnabled <- cursor.downField(consts.enable).as[Option[Boolean]]
      result <- if (isEnabled.getOrElse(true)) decoding.map(Some.apply) else Right(None)
    } yield result
  }

  private def fileDecoder(basePath: Path): Decoder[JFile] =
    Decoder
      .decodeString
      .map { str => basePath.resolve(Paths.get(str)).toFile }
}
