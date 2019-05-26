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
package tech.beshu.ror.acl.utils

import cats.{Applicative, Order}
import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.CursorOp.DownField
import io.circe._
import io.circe.generic.extras
import io.circe.generic.extras.Configuration
import io.circe.parser._
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.Value.ConvertError
import tech.beshu.ror.acl.blocks.Variable.ResolvedValue
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.{Reason, ValueLevelCreationError}
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult._

import scala.collection.SortedSet
import scala.language.higherKinds

object CirceOps {

  object DecoderHelpers {
    val decodeStringLike: Decoder[String] = Decoder.decodeString.or(Decoder.decodeInt.map(_.show))

    // todo: merge with above
    implicit val decodeStringLikeNonEmpty: Decoder[NonEmptyString] =
      Decoder.decodeString.or(Decoder.decodeInt.map(_.show)).emap(NonEmptyString.from)

    def decodeStringLikeOrNonEmptySet[T: Order](fromString: String => T): Decoder[NonEmptySet[T]] =
      decodeStringLike
        .map(NonEmptySet.one(_))
        .or(Decoder.decodeNonEmptySet[String])
        .map(_.map(fromString))

    def decodeNonEmptyStringLikeOrNonEmptySet[T: Order](fromString: NonEmptyString => T): Decoder[NonEmptySet[T]] =
      decodeStringLikeNonEmpty
        .map(NonEmptySet.one(_))
        .or(Decoder.decodeNonEmptySet[NonEmptyString])
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

    def decodeStringLikeOrSet[T: Order : Decoder]: Decoder[Set[T]] = {
      decodeStringLike.map(Set(_)).or(Decoder.decodeSet[String]).emap { set =>
        val (errorsSet, valuesSet) = set.foldLeft((Set.empty[String], Set.empty[T])) {
          case ((errors, values), elem) =>
            Decoder[T].decodeJson(Json.fromString(elem)) match {
              case Right(value) => (errors, values + value)
              case Left(error) => (errors + error.message, values)
            }
        }
        if (errorsSet.nonEmpty) Left(errorsSet.mkString(","))
        else Right(valuesSet)
      }
    }

    def decodeStringLikeWithVarResolvedInPlace(implicit resolver: StaticVariablesResolver): Decoder[String] = {
      SyncDecoderCreator
        .from(decodeStringLike)
        .emapE { variable =>
          resolver.resolve(variable) match {
            case Some(resolved) => Right(resolved)
            case None => Left(ValueLevelCreationError(Message(s"Cannot resolve variable: $variable")))
          }
        }
        .decoder
    }

    def valueDecoder[T](convert: ResolvedValue => Either[Value.ConvertError, T]): Decoder[Either[ConvertError, Value[T]]] =
      DecoderHelpers
        .decodeStringLike
        .map { str => Value.fromString(str, convert) }

    def alwaysRightValueDecoder[T](convert: ResolvedValue => T): Decoder[Value[T]] =
      SyncDecoderCreator
        .from(valueDecoder[T](rv => Right(convert(rv))))
        .emapE {
          _.left.map(error => AclCreationError.RulesLevelCreationError(Message(error.msg)))
        }
        .decoder

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

    def decodeFieldList[T, F[_] : Applicative](name: String,
                                               errorCreator: Reason => AclCreationError = ValueLevelCreationError.apply)
                                              (implicit decoder: ADecoder[F, T]): decoder.DECODER[FieldListResult[T]] = {
      decoder
        .creator
        .instance[FieldListResult[T]] { c =>
        val fApplicative = implicitly[Applicative[F]]
        c.downField(name) match {
          case _: FailedCursor =>
            fApplicative.pure(Right(NoField))
          case hc =>
            hc.values match {
              case None =>
                fApplicative.pure(Right(FieldListValue(Nil)))
              case Some(_) =>
                decoder.creator
                  .list[T](decoder)
                  .tryDecode(hc)
                  .map {
                    _.map(FieldListValue.apply)
                      .left
                      .map { df =>
                        df.overrideDefaultErrorWith(errorCreator {
                          hc.focus match {
                            case Some(json) =>
                              MalformedValue(json)
                            case None =>
                              val ruleName = df.history.headOption.collect { case df: DownField => df.k }.getOrElse("")
                              Message(s"Malformed definition $ruleName")
                          }
                        })
                      }
                  }
            }
        }
      }
    }

    def failed[T](error: AclCreationError): Decoder[T] = {
      Decoder.failed(DecodingFailureOps.fromError(error))
    }

    sealed trait FieldListResult[+T]
    object FieldListResult {
      case object NoField extends FieldListResult[Nothing]
      final case class FieldListValue[T](list: List[T]) extends FieldListResult[T]
    }
  }

  implicit class DecoderOps[A](val decoder: Decoder[A]) extends AnyVal {
    def toSyncDecoder: SyncDecoder[A] = SyncDecoderCreator.from(decoder)
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

  object AclCreationErrorCoders {
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

  implicit class HCursorOps(val value: HCursor) extends AnyVal {
    def downFields(field: String, fields: String*): ACursor = {
      fields.toList.foldLeft(value.downField(field)) {
        case (_: FailedCursor, nextField) => value.downField(nextField)
        case (found: HCursor, _) => found
        case (other, _) => other
      }
    }

    def downNonEmptyField(name: String): Decoder.Result[NonEmptyString] = {
      import tech.beshu.ror.acl.factory.decoders.common.nonEmptyStringDecoder
      downFields(name).asWithError[NonEmptyString](s"Field $name cannot be empty")
    }

    def downNonEmptyOptionalField(name: String): Decoder.Result[Option[NonEmptyString]] = {
      import tech.beshu.ror.acl.factory.decoders.common.nonEmptyStringDecoder
      downFields(name).asWithError[Option[NonEmptyString]](s"Field $name cannot be empty")
    }

  }

  implicit class ACursorOps(val value: ACursor) extends AnyVal {

    def asWithError[T : Decoder](error: String): Decoder.Result[T] =
      value
        .as[T](implicitly[Decoder[T]])
        .left
        .map(_.overrideDefaultErrorWith(ValueLevelCreationError(Message(error))))
  }

}
