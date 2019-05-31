package tech.beshu.ror.configuration

import better.files._
import java.io.{File => JFile}
import java.nio.file.Path

import io.circe.Decoder
import monix.eval.Task
import io.circe.yaml._
import scala.collection.JavaConverters._

import tech.beshu.ror.configuration.SslConfiguration.{Cipher, KeyAlias, KeyPass, KeystorePassword, Protocol}

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

  def load(path: Path): Task[RorSsl] = Task {
    File(path).fileReader { reader =>
      parser
        .parse(reader)
        .left.map(_.message)
        .right
        .flatMap { json =>
          SslDecoders
            .rorSslDecoder
            .decodeJson(json)
            .left.map(_.message)
        }
        .fold(
          msg => throw new IllegalArgumentException(s"Invalid SSL configuration: $msg"),
          identity
        )
    }
  }
}

private object SslDecoders {

  // todo: better errors?

  private implicit val keystoreFileDecoder: Decoder[JFile] = Decoder.decodeString.map(File(_)).map(_.toJava)
  private implicit val keystorePasswordDecoder: Decoder[KeystorePassword] = Decoder.decodeString.map(KeystorePassword.apply)
  private implicit val keyPassDecoder: Decoder[KeyPass] = Decoder.decodeString.map(KeyPass.apply)
  private implicit val keyAliasDecoder: Decoder[KeyAlias] = Decoder.decodeString.map(KeyAlias.apply)
  private implicit val cipherDecoder: Decoder[Cipher] = Decoder.decodeString.map(Cipher.apply)
  private implicit val protocolDecoder: Decoder[Protocol] = Decoder.decodeString.map(Protocol.apply)

  private implicit val sslConfigurationDecoder: Decoder[SslConfiguration] = Decoder.instance { c =>
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
  implicit val rorSslDecoder: Decoder[RorSsl] = Decoder.instance { c =>
    for {
      externalSsl <- c.downField("ssl").as[Option[SslConfiguration]]
      interNodeSsl <- c.downField("ssl_internode").as[Option[SslConfiguration]]
    } yield RorSsl(externalSsl, interNodeSsl)
  }

}
