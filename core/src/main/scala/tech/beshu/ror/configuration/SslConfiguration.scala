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
import io.circe.yaml._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.SslConfiguration.SSLSettingsMalformedException

import scala.language.implicitConversions

final case class RorSsl(externalSsl: Option[SslConfiguration],
                        interNodeSsl: Option[SslConfiguration])

object RorSsl extends Logging {

  val noSsl = RorSsl(None, None)

  def load(esConfigFolderPath: Path): Task[RorSsl] = Task {
    implicit val sslDecoder: Decoder[RorSsl] = SslDecoders.rorSslDecoder(esConfigFolderPath)
    val esConfig = File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath)
    loadSslConfigFromFile(esConfig)
      .fold(
        _ => throw SSLSettingsMalformedException(s"Invalid SSL configuration"),
        {
          case RorSsl(None, None) => fallbackToRorConfig(esConfigFolderPath)
          case ssl => ssl
        }
      )
  }

  private def fallbackToRorConfig(esConfigFolderPath: Path)
                                 (implicit rorSslDecoder: Decoder[RorSsl]) = {
    val rorConfig = FileConfigLoader.create(esConfigFolderPath).rawConfigFile
    logger.info(s"Cannot find SSL configuration is elasticsearch.yml, trying: ${rorConfig.pathAsString}")
    if(rorConfig.exists) {
      loadSslConfigFromFile(rorConfig)
        .fold(
          _ => throw SSLSettingsMalformedException(s"Invalid SSL configuration"),
          identity
        )
    } else {
      RorSsl.noSsl
    }
  }

  private def loadSslConfigFromFile(file: File)
                                   (implicit rorSslDecoder: Decoder[RorSsl]) = {
    file.fileReader { reader =>
      parser
        .parse(reader)
        .left.map(_.message)
        .right
        .flatMap { json =>
          rorSslDecoder
            .decodeJson(json)
            .left.map(_.message)
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
                                  verifyClientAuth: Boolean)

object SslConfiguration {

  type Error = String

  final case class KeystorePassword(value: String)
  final case class KeyPass(value: String)
  final case class KeyAlias(value: String)
  final case class Cipher(value: String)
  final case class Protocol(value: String)

  final case class SSLSettingsMalformedException(message: String) extends Exception
}

private object SslDecoders {

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
        keystoreFile <- c.downField("keystore_file").as[JFile]
        keystorePassword <- c.downField("keystore_pass").as[Option[KeystorePassword]]
        keyPass <- c.downField("key_pass").as[Option[KeyPass]]
        keyAlias <- c.downField("key_alias").as[Option[KeyAlias]]
        ciphers <- c.downField("allowed_ciphers").as[Option[Set[Cipher]]]
        protocols <- c.downField("allowed_protocols").as[Option[Set[Protocol]]]
        verify <- c.downField("verification").as[Option[Boolean]]
      } yield SslConfiguration(
        keystoreFile,
        keystorePassword,
        keyPass,
        keyAlias,
        protocols.getOrElse(Set.empty[Protocol]),
        ciphers.getOrElse(Set.empty[Cipher]),
        verify.getOrElse(false)
      )
    }
  }

  implicit def rorSslDecoder(basePath: Path): Decoder[RorSsl] = Decoder.instance { c =>
    implicit val sslConfigDecoder: Decoder[Option[SslConfiguration]] = sslConfigurationDecoder(basePath)
    for {
      interNodeSsl <- c.downField("readonlyrest").downField("ssl_internode").as[Option[Option[SslConfiguration]]]
      externalSsl <- c.downField("readonlyrest").downField("ssl").as[Option[Option[SslConfiguration]]]
    } yield RorSsl(externalSsl.flatten, interNodeSsl.flatten)
  }

  private def whenEnabled(cursor: HCursor)(decoding: => Either[DecodingFailure, SslConfiguration]) = {
    for {
      isEnabled <- cursor.downField("enable").as[Option[Boolean]]
      result <- if(isEnabled.getOrElse(true)) decoding.map(Some.apply) else Right(None)
    } yield result
  }
}
