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
  def serverCertificateConfiguration: SslConfiguration.ServerCertificateConfiguration
  def clientCertificateConfiguration: Option[SslConfiguration.ClientCertificateConfiguration]
  def allowedProtocols: Set[SslConfiguration.Protocol]
  def allowedCiphers: Set[SslConfiguration.Cipher]
}

object SslConfiguration {

  final case class KeystorePassword(value: String)
  final case class KeystoreFile(value: JFile)
  final case class TruststorePassword(value: String)
  final case class TruststoreFile(value: JFile)
  final case class ServerCertificateKeyFile(value: JFile)
  final case class ServerCertificateFile(value: JFile)
  final case class ClientTrustedCertificateFile(value: JFile)
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

  final case class ExternalSslConfiguration(serverCertificateConfiguration: ServerCertificateConfiguration,
                                            clientCertificateConfiguration: Option[ClientCertificateConfiguration],
                                            allowedProtocols: Set[SslConfiguration.Protocol],
                                            allowedCiphers: Set[SslConfiguration.Cipher],
                                            clientAuthenticationEnabled: Boolean) extends SslConfiguration

  final case class InternodeSslConfiguration(serverCertificateConfiguration: ServerCertificateConfiguration,
                                             clientCertificateConfiguration: Option[ClientCertificateConfiguration],
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
    val serverCertificateKeyFile = "server_certificate_key_file"
    val serverCertificateFile = "server_certificate_file"
    val clientTrustedCertificateFile = "client_trusted_certificate_file"
  }

  final case class CommonSslProperties(serverCertificateConfiguration: ServerCertificateConfiguration,
                                       clientCertificateConfiguration: Option[ClientCertificateConfiguration],
                                       allowedProtocols: Set[SslConfiguration.Protocol],
                                       allowedCiphers: Set[SslConfiguration.Cipher],
                                       verification: Option[Boolean])


  private implicit val keystorePasswordDecoder: Decoder[KeystorePassword] = DecoderHelpers.decodeStringLike.map(KeystorePassword.apply)
  private implicit val truststorePasswordDecoder: Decoder[TruststorePassword] = DecoderHelpers.decodeStringLike.map(TruststorePassword.apply)
  private implicit val keyPassDecoder: Decoder[KeyPass] = DecoderHelpers.decodeStringLike.map(KeyPass.apply)
  private implicit val keyAliasDecoder: Decoder[KeyAlias] = DecoderHelpers.decodeStringLike.map(KeyAlias.apply)
  private implicit val cipherDecoder: Decoder[Cipher] = DecoderHelpers.decodeStringLike.map(Cipher.apply)
  private implicit val protocolDecoder: Decoder[Protocol] = DecoderHelpers.decodeStringLike.map(Protocol.apply)

  private def clientCertificateConfigurationDecoder(basePath: Path): Decoder[Option[ClientCertificateConfiguration]] = {
    val jFileDecoder: Decoder[JFile] = fileDecoder(basePath)
    implicit val truststoreFileDecoder = jFileDecoder.map(TruststoreFile)
    implicit val clientTrustedCertificateFileDecoder = jFileDecoder.map(ClientTrustedCertificateFile)

    val truststoreBasedClientCertificateConfigurationDecoder: Decoder[ClientCertificateConfiguration] =
      Decoder.forProduct2(consts.truststoreFile, consts.truststorePass)(ClientCertificateConfiguration.TruststoreBasedConfiguration.apply)
    val fileBasedClientCertificateConfigurationDecoder: Decoder[ClientCertificateConfiguration] =
      Decoder.forProduct1(consts.clientTrustedCertificateFile)(ClientCertificateConfiguration.FileBasedConfiguration)
    Decoder.instance { c =>
      val truststoreBasedKeys = Set(consts.truststoreFile, consts.truststorePass)
      val fileBasedKeys = Set(consts.clientTrustedCertificateFile)
      val presentKeys = c.keys.fold[Set[String]](Set.empty)(_.toSet)
      if (presentKeys.intersect(truststoreBasedKeys).size > 1 && presentKeys.intersect(fileBasedKeys).size > 1) {
        Left(DecodingFailure(s"Fields [$fileBasedKeys] and [$truststoreBasedKeys] could not be present in the same configuration section", List.empty))
      } else if (presentKeys.intersect(truststoreBasedKeys ++ fileBasedKeys).isEmpty) {
        Right(None)
      } else {
        truststoreBasedClientCertificateConfigurationDecoder
          .or(fileBasedClientCertificateConfigurationDecoder)
          .apply(c)
          .map(Option.apply)
      }
    }
  }

  private def serverCertificateConfigurationDecoder(basePath: Path): Decoder[ServerCertificateConfiguration] = {
    val jFileDecoder: Decoder[JFile] = fileDecoder(basePath)
    implicit val keystoreFileDecoder = jFileDecoder.map(KeystoreFile)
    implicit val serverCertificateFileDecoder = jFileDecoder.map(ServerCertificateFile)
    implicit val serverCertificateKeyFileDecoder = jFileDecoder.map(ServerCertificateKeyFile)
    val keystoreBasedServerCertificateConfigurationDecoder: Decoder[ServerCertificateConfiguration] =
      Decoder.forProduct4(consts.keystoreFile, consts.keystorePass, consts.keyAlias, consts.keyPass)(ServerCertificateConfiguration.KeystoreBasedConfiguration.apply)
    val fileBasedServerCertificateConfigurationDecoder: Decoder[ServerCertificateConfiguration] =
      Decoder.forProduct2(consts.serverCertificateKeyFile, consts.serverCertificateFile)(ServerCertificateConfiguration.FileBasedConfiguration.apply)
    Decoder.instance { c =>
      val keystoreBasedKeys = Set(consts.keystoreFile, consts.keystorePass, consts.keyPass, consts.keyAlias)
      val fileBasedKeys = Set(consts.serverCertificateKeyFile, consts.serverCertificateFile)
      val presentKeys = c.keys.fold[Set[String]](Set.empty)(_.toSet)
      if (presentKeys.intersect(keystoreBasedKeys).size > 1 && presentKeys.intersect(fileBasedKeys).size > 1) {
        Left(DecodingFailure(s"Fields [$fileBasedKeys] and [$keystoreBasedKeys] could not be present in the same configuration section", List.empty))
      } else {
        keystoreBasedServerCertificateConfigurationDecoder.or(fileBasedServerCertificateConfigurationDecoder).apply(c)
      }
    }
  }

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
          serverCertificateConfiguration = sslCommonProperties.serverCertificateConfiguration,
          clientCertificateConfiguration = sslCommonProperties.clientCertificateConfiguration,
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
          serverCertificateConfiguration = sslCommonProperties.serverCertificateConfiguration,
          clientCertificateConfiguration = sslCommonProperties.clientCertificateConfiguration,
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
    implicit val serverCertificateFileDecoder = jFileDecoder.map(ServerCertificateFile)
    implicit val serverCertificateKeyFileDecoder = jFileDecoder.map(ServerCertificateKeyFile)
    implicit val clientTrustedCertificateFileDecoder = jFileDecoder.map(ClientTrustedCertificateFile)
    for {
      ciphers <- c.downField(consts.allowedCiphers).as[Option[Set[Cipher]]]
      protocols <- c.downField(consts.allowedProtocols).as[Option[Set[Protocol]]]
      verification <- c.downField(consts.verification).as[Option[Boolean]]
      serverCertificateConfiguration <- serverCertificateConfigurationDecoder(basePath).apply(c)
      clientCertificateConfiguration <- clientCertificateConfigurationDecoder(basePath).apply(c)
    } yield
      CommonSslProperties(
        serverCertificateConfiguration = serverCertificateConfiguration,
        clientCertificateConfiguration = clientCertificateConfiguration,
        allowedProtocols = protocols.getOrElse(Set.empty[Protocol]),
        allowedCiphers = ciphers.getOrElse(Set.empty[Cipher]),
        verification = verification,
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
