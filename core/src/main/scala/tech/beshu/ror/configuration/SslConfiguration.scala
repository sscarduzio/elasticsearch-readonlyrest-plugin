package tech.beshu.ror.configuration

import java.io.{File => JFile}
import java.nio.file.{Path, Paths}

import better.files._
import io.circe.Decoder
import io.circe.yaml._
import monix.eval.Task
import tech.beshu.ror.configuration.SslConfiguration._

import scala.collection.JavaConverters._
import scala.language.implicitConversions

final case class RorSsl(externalSsl: Option[SslConfiguration], // todo: fox ext verification = false?
                        interNodeSsl: Option[SslConfiguration]) // todo: fox inter verification = true?

final case class SslConfiguration(keystoreFile: JFile,
                                  keystorePassword: Option[KeystorePassword],
                                  keyPass: Option[KeyPass],
                                  keyAlias: Option[KeyAlias],
                                  allowedProtocols: java.util.Set[Protocol],
                                  allowedCiphers: java.util.Set[Cipher],
                                  verifyClientAuth: Boolean)

object SslConfiguration {

  type Error = String

  final case class KeystorePassword(value: String)
  final case class KeyPass(value: String)
  final case class KeyAlias(value: String)
  final case class Cipher(value: String)
  final case class Protocol(value: String)

  def load(esConfigFolderPath: Path): Task[RorSsl] = Task {
    implicit val sslDecoder: Decoder[RorSsl] = SslDecoders.rorSslDecoder(esConfigFolderPath)
    val esConfig = File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath)
    loadSslConfigFromFile(esConfig)
      .fold(
        error => throw new IllegalArgumentException(s"Invalid SSL configuration: $error"),
        {
          case RorSsl(None, None) => fallbackToRorConfig(esConfigFolderPath)
          case ssl => ssl
        }
      )
  }

  private def fallbackToRorConfig(esConfigFolderPath: Path)
                                 (implicit rorSslDecoder: Decoder[RorSsl]) = {
    val rorConfig = FileConfigLoader.create(esConfigFolderPath).rawConfigFile
    loadSslConfigFromFile(rorConfig)
      .fold(
        error => throw new IllegalArgumentException(s"Invalid SSL configuration: $error"),
        identity
      )
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

private object SslDecoders {

  // todo: better errors?

  private implicit def keystoreFileDecoder(basePath: Path): Decoder[JFile] =
    Decoder
      .decodeString
      .map { str => basePath.resolve(Paths.get(str)).toFile }

  private implicit val keystorePasswordDecoder: Decoder[KeystorePassword] = Decoder.decodeString.map(KeystorePassword.apply)
  private implicit val keyPassDecoder: Decoder[KeyPass] = Decoder.decodeString.map(KeyPass.apply)
  private implicit val keyAliasDecoder: Decoder[KeyAlias] = Decoder.decodeString.map(KeyAlias.apply)
  private implicit val cipherDecoder: Decoder[Cipher] = Decoder.decodeString.map(Cipher.apply)
  private implicit val protocolDecoder: Decoder[Protocol] = Decoder.decodeString.map(Protocol.apply)

  private implicit def sslConfigurationDecoder(basePath: Path): Decoder[SslConfiguration] = Decoder.instance { c =>
    implicit val jFileDecoder: Decoder[JFile] = keystoreFileDecoder(basePath)
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
      protocols.getOrElse(Set.empty[Protocol]).asJava,
      ciphers.getOrElse(Set.empty[Cipher]).asJava,
      verify.getOrElse(false)
    )
  }

  // todo: enable
  implicit def rorSslDecoder(basePath: Path): Decoder[RorSsl] = Decoder.instance { c =>
    implicit val sslConfigDecoder: Decoder[SslConfiguration] = sslConfigurationDecoder(basePath)
    for {
      externalSsl <- c.downField("readonlyrest").downField("ssl").as[Option[SslConfiguration]]
      interNodeSsl <- c.downField("readonlyrest").downField("ssl_internode").as[Option[SslConfiguration]]
    } yield RorSsl(externalSsl, interNodeSsl)
  }

}
