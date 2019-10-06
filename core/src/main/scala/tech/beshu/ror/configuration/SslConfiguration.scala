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
import tech.beshu.ror.utils.yaml

import scala.language.implicitConversions

final case class RorSsl(externalSsl: Option[SslConfiguration],
                        interNodeSsl: Option[SslConfiguration])

object RorSsl extends Logging {

  val noSsl = RorSsl(None, None)

  final case class MalformedSettings(message: String)

  def load(esConfigFolderPath: Path): Task[Either[MalformedSettings, RorSsl]] = Task {
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
                                 (implicit rorSslDecoder: Decoder[RorSsl]) = {
    val rorConfig = FileConfigLoader.create(esConfigFolderPath).rawConfigFile
    logger.info(s"Cannot find SSL configuration is elasticsearch.yml, trying: ${rorConfig.pathAsString}")
    if(rorConfig.exists) {
      loadSslConfigFromFile(rorConfig)
    } else {
      Right(RorSsl.noSsl)
    }
  }

  private def loadSslConfigFromFile(file: File)
                                   (implicit rorSslDecoder: Decoder[RorSsl]) = {
    file.fileReader { reader =>
      yaml
        .parser
        .parse(reader)
        .left.map(e => MalformedSettings(s"Cannot parse file ${file.pathAsString} content. Cause: ${e.message}"))
        .right
        .flatMap { json =>
          rorSslDecoder
            .decodeJson(json)
            .left.map(e => MalformedSettings(s"Invalid ROR SSL configuration"))
        }
    }
  }
}

final case class SslConfiguration(keystoreFile: JFile,
                                  keystorePassword: Option[SslConfiguration.KeystorePassword],
                                  keyPass: Option[SslConfiguration.KeyPass],
                                  keyAlias: Option[SslConfiguration.KeyAlias],
                                  allowedProtocols: Set[SslConfiguration.Protocol],
                                  allowedCiphers: Set[SslConfiguration.Cipher],
                                  clientAuthenticationEnabled: Boolean,
                                  certificateVerificationEnabled: Boolean)

object SslConfiguration {

  type Error = String

  final case class KeystorePassword(value: String)
  final case class KeyPass(value: String)
  final case class KeyAlias(value: String)
  final case class Cipher(value: String)
  final case class Protocol(value: String)
}

private object SslDecoders {

  object consts {
    val rorSection = "readonlyrest"
    val forceLoadFromFile = "force_load_from_file"
    val externalSsl = "ssl"
    val internodeSsl = "ssl_internode"
    val keystoreFile = "keystore_file"
    val keystorePass = "keystore_pass"
    val keyPass = "key_pass"
    val keyAlias = "key_alias"
    val allowedCiphers = "allowed_ciphers"
    val allowedProtocols = "allowed_protocols"
    val certificateVerification = "certificate_verification"
    val clientAuthentication = "client_authentication"
    val verification = "verification"
    val enable = "enable"
  }

  import tech.beshu.ror.configuration.SslConfiguration._

  private implicit def keystoreFileDecoder(basePath: Path): Decoder[JFile] =
    Decoder
      .decodeString
      .map { str => basePath.resolve(Paths.get(str)).toFile }

  private implicit val keystorePasswordDecoder: Decoder[KeystorePassword] = Decoder.decodeString.map(KeystorePassword.apply)
  private implicit val keyPassDecoder: Decoder[KeyPass] = Decoder.decodeString.map(KeyPass.apply)
  private implicit val keyAliasDecoder: Decoder[KeyAlias] = Decoder.decodeString.map(KeyAlias.apply)
  private implicit val cipherDecoder: Decoder[Cipher] = Decoder.decodeString.map(Cipher.apply)
  private implicit val protocolDecoder: Decoder[Protocol] = Decoder.decodeString.map(Protocol.apply)

  private implicit def sslConfigurationDecoder(basePath: Path): Decoder[Option[SslConfiguration]] = Decoder.instance { c =>
    implicit val jFileDecoder: Decoder[JFile] = keystoreFileDecoder(basePath)
    whenEnabled(c) {
      for {
        keystoreFile <- c.downField(consts.keystoreFile).as[JFile]
        keystorePassword <- c.downField(consts.keystorePass).as[Option[KeystorePassword]]
        keyPass <- c.downField(consts.keyPass).as[Option[KeyPass]]
        keyAlias <- c.downField(consts.keyAlias).as[Option[KeyAlias]]
        ciphers <- c.downField(consts.allowedCiphers).as[Option[Set[Cipher]]]
        protocols <- c.downField(consts.allowedProtocols).as[Option[Set[Protocol]]]
        clientAuthentication <- c.downField(consts.clientAuthentication).as[Option[Boolean]]
        certificateVerification <- c.downField(consts.certificateVerification).as[Option[Boolean]]
        verification <- c.downField(consts.verification).as[Option[Boolean]]
      } yield SslConfiguration(
        keystoreFile = keystoreFile,
        keystorePassword = keystorePassword,
        keyPass = keyPass,
        keyAlias = keyAlias,
        allowedProtocols = protocols.getOrElse(Set.empty[Protocol]),
        allowedCiphers = ciphers.getOrElse(Set.empty[Cipher]),
        clientAuthenticationEnabled =  clientAuthentication.orElse(verification).getOrElse(false),
        certificateVerificationEnabled =  certificateVerification.orElse(verification).getOrElse(false)
      )
    }
  }

  implicit def rorSslDecoder(basePath: Path): Decoder[RorSsl] = Decoder.instance { c =>
    implicit val sslConfigDecoder: Decoder[Option[SslConfiguration]] = sslConfigurationDecoder(basePath)
    for {
      interNodeSsl <- c.downField(consts.rorSection).downField(consts.internodeSsl).as[Option[Option[SslConfiguration]]]
      externalSsl <- c.downField(consts.rorSection).downField(consts.externalSsl).as[Option[Option[SslConfiguration]]]
    } yield RorSsl(externalSsl.flatten, interNodeSsl.flatten)
  }

  private def whenEnabled(cursor: HCursor)(decoding: => Either[DecodingFailure, SslConfiguration]) = {
    for {
      isEnabled <- cursor.downField(consts.enable).as[Option[Boolean]]
      result <- if(isEnabled.getOrElse(true)) decoding.map(Some.apply) else Right(None)
    } yield result
  }
}
