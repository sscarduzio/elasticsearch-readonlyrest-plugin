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
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}

import scala.collection.SortedSet

object CirceOps {

  object DecoderHelpers {
    val decodeStringLike: Decoder[String] = Decoder.decodeString.or(Decoder.decodeInt.map(_.show))

    def decodeStringLikeOrNonEmptySet[T: Order](fromString: String => T): Decoder[NonEmptySet[T]] =
      decodeStringLike
        .map(NonEmptySet.one(_))
        .or(Decoder.decodeNonEmptySet[String])
        .map(_.map(fromString))

    def decodeStringLikeOrNonEmptySetE[T: Order](fromString: String => Either[String, T]): Decoder[NonEmptySet[T]] =
      decodeStringLike.map(NonEmptySet.one(_)).or(Decoder.decodeNonEmptySet[String]).emap { set =>
        val (errorsSet, valuesSet) = set.foldLeft((Set.empty[String], Set.empty[T])) {
          case ((errors, values), elem) =>
            fromString(elem) match {
              case Right(value) => (errors, values + value)
              case Left(error) => (errors + error, values)
            }
        }
        if (errorsSet.nonEmpty) Left(errorsSet.mkString(","))
        else Right(NonEmptySet.fromSetUnsafe(SortedSet.empty[T] ++ valuesSet))
      }

    def decodeStringLikeOrNonEmptySet[T: Order : Decoder]: Decoder[NonEmptySet[T]] =
      decodeStringLikeOrNonEmptySetE { str =>
        Decoder[T].decodeJson(Json.fromString(str)).left.map(_.message)
      }

    def valueDecoder[T](convert: ResolvedValue => T): Decoder[Value[T]] =
      DecoderHelpers.decodeStringLike.map(str => Value.fromString(str, convert))

    def decodeStringOrJson[T](simpleDecoder: Decoder[T], expandedDecoder: Decoder[T]): Decoder[T] = {
      Decoder
        .decodeJson
        .flatMap { json =>
          json.asString match {
            case Some(_) => simpleDecoder
            case None => expandedDecoder
          }
        }
    }

    def decodeFieldList[T: Decoder](name: String): Decoder[FieldListResult[T]] = {
      Decoder
        .decodeJson
        .emap { json =>
          json \\ name match {
            case Nil =>
              Right(NoField)
            case x :: Nil if x.isNull =>
              Right(FieldListValue(Nil))
            case xs =>
              implicitly[Decoder[List[T]]]
                .decodeJson(xs.head)
                .map(FieldListValue.apply)
                .left.map(_.message)
          }
        }
    }

    sealed trait FieldListResult[+T]
    object FieldListResult {
      case object NoField extends FieldListResult[Nothing]
      final case class FieldListValue[T](list: List[T]) extends FieldListResult[T]
    }
  }

  implicit class DecoderOps[A](val decoder: Decoder[A]) extends AnyVal {
    def withError(error: AclCreationError): Decoder[A] = {
      Decoder.instance { c =>
        decoder(c).left.map(_.overrideDefaultErrorWith(error))
      }
    }

    def withError(errorCreator: Json => AclCreationError): Decoder[A] = {
      Decoder.instance { c =>
        decoder(c).left.map(_.overrideDefaultErrorWith(errorCreator(c.value)))
      }
    }

    def emapE[B](f: A => Either[AclCreationError, B]): Decoder[B] =
      decoder.emap { a => f(a).left.map(AclCreationErrorCoders.stringify) }
  }

  implicit class DecodingFailureOps(val decodingFailure: DecodingFailure) extends AnyVal {

    import AclCreationErrorCoders._

    def overrideDefaultErrorWith(error: AclCreationError): DecodingFailure = {
      if (aclCreationError.isDefined) decodingFailure
      else decodingFailure.withMessage(stringify(error))
    }

    def aclCreationError: Option[AclCreationError] =
      parse(decodingFailure.message).flatMap(Decoder[AclCreationError].decodeJson).toOption

  }

  object DecodingFailureOps {

    import AclCreationErrorCoders._

    def fromError(error: AclCreationError): DecodingFailure =
      DecodingFailure(Encoder[AclCreationError].apply(error).noSpaces, Nil)
  }

  private[this] object AclCreationErrorCoders {
    private implicit val config: Configuration = Configuration.default.withDiscriminator("type")
    implicit val aclCreationErrorEncoder: Encoder[AclCreationError] = {
      implicit val _ = extras.semiauto.deriveEncoder[Reason]
      extras.semiauto.deriveEncoder
    }
    implicit val aclCreationErrorDecoder: Decoder[AclCreationError] = {
      implicit val _ = extras.semiauto.deriveDecoder[Reason]
      extras.semiauto.deriveDecoder
    }

    def stringify(error: AclCreationError): String = Encoder[AclCreationError].apply(error).noSpaces
  }

}
