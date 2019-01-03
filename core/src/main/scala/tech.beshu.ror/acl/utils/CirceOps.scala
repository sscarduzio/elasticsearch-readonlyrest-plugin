package tech.beshu.ror.acl.utils

import cats.Order
import cats.data.NonEmptySet
import cats.implicits._
import io.circe.generic.extras.Configuration
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.generic.extras
import io.circe.parser._
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Variable.ResolvedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError

import scala.collection.SortedSet

object CirceOps {

  object DecoderOps {
    val decodeStringLike: Decoder[String] = Decoder.decodeString.or(Decoder.decodeInt.map(_.show))
    def decodeStringLikeOrNonEmptySet[T : Order](fromString: String => T): Decoder[NonEmptySet[T]] =
      decodeStringLike.map(NonEmptySet.one(_)).or(Decoder.decodeNonEmptySet[String]).map(_.map(fromString))

    def decodeStringLikeOrNonEmptySetE[T : Order](fromString: String => Either[String, T]): Decoder[NonEmptySet[T]] =
      decodeStringLike.map(NonEmptySet.one(_)).or(Decoder.decodeNonEmptySet[String]).emap { set =>
        val (errorsSet, valuesSet) = set.foldLeft((Set.empty[String], Set.empty[T])) {
          case ((errors, values), elem) =>
            fromString(elem) match {
              case Right(value) => (errors, values + value)
              case Left(error) => (errors + error, values)
            }
        }
        if(errorsSet.nonEmpty) Left(errorsSet.mkString(","))
        else Right(NonEmptySet.fromSetUnsafe(SortedSet.empty[T] ++ valuesSet))
      }

    def decodeStringLikeOrNonEmptySet[T : Order : Decoder]: Decoder[NonEmptySet[T]] =
      decodeStringLikeOrNonEmptySetE { str =>
        Decoder[T].decodeJson(Json.fromString(str)).left.map(_.message)
      }

    def valueDecoder[T](convert: ResolvedValue => T): Decoder[Value[T]] =
      DecoderOps.decodeStringLike.map(str => Value.fromString(str, convert))

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
