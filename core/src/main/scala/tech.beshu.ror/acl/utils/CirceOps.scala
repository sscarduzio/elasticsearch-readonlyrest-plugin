package tech.beshu.ror.acl.utils

import cats.implicits._
import io.circe.generic.extras.Configuration
import io.circe.{Decoder, DecodingFailure, Encoder}
import io.circe.generic.extras
import io.circe.parser._
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError

object CirceOps {

  object DecoderOps {
    val decodeStringOrNumber: Decoder[String] = Decoder.decodeString.or(Decoder.decodeInt.map(_.show))
  }

  implicit class DecodingFailureOps(val decodingFailure: DecodingFailure) extends AnyVal {
    import DecodingFailureOps._

    def overrideDefaultErrorWith(error: AclCreationError): DecodingFailure = {
      if(aclCreationError.isDefined) decodingFailure
      else decodingFailure.withMessage(Encoder[AclCreationError].apply(error).noSpaces)
    }

    def aclCreationError: Option[AclCreationError] =
      parse(decodingFailure.message).flatMap(Decoder[AclCreationError].decodeJson).toOption

  }

  object DecodingFailureOps {
    private implicit val config: Configuration = Configuration.default.withDiscriminator("type")
    private implicit val aclCreationErrorEncoder: Encoder[AclCreationError] = extras.semiauto.deriveEncoder
    private implicit val aclCreationErrorDecoder: Decoder[AclCreationError] = extras.semiauto.deriveDecoder

    def fromError(error: AclCreationError): DecodingFailure =
      DecodingFailure(Encoder[AclCreationError].apply(error).noSpaces, Nil)
  }
}
