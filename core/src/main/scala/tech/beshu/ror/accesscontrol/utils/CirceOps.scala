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
package tech.beshu.ror.accesscontrol.utils

import cats.data.NonEmptySet
import cats.implicits.*
import cats.{Applicative, Order}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.*
import io.circe.CursorOp.DownField
import io.circe.parser.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.CreationError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator, RuntimeSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{Reason, ValueLevelCreationError}
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.show.logs.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers.FieldListResult.*
import tech.beshu.ror.utils.CirceOps.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.collection.immutable.SortedSet

object CirceOps {

  object DecoderHelpers {
    val decodeStringLike: Decoder[String] = Decoder.decodeString.or(Decoder.decodeInt.map(_.show))

    implicit val decodeStringLikeNonEmpty: Decoder[NonEmptyString] = decodeStringLike.emap(NonEmptyString.from)

    implicit def decodeUniqueNonEmptyList[T](implicit decodeT: Decoder[T]): Decoder[UniqueNonEmptyList[T]] =
      Decoder.decodeNonEmptyList(decodeT).map(UniqueNonEmptyList.fromNonEmptyList)

    implicit def decodeUniqueList[T](implicit decodeT: Decoder[T]): Decoder[UniqueList[T]] =
      Decoder.decodeList[T].map(UniqueList.fromIterable)

    def decodeStringLikeOrNonEmptySet[T: Order](fromString: String => T): Decoder[NonEmptySet[T]] =
      decodeStringLike
        .map(NonEmptySet.one(_))
        .or(Decoder.decodeNonEmptySet[String])
        .map(_.map(fromString))

    def decodeStringLikeOrNonEmptySet[T: Order : Decoder]: Decoder[NonEmptySet[T]] =
      decodeStringLikeOrNonEmptySetE { str =>
        Decoder[T].decodeJson(Json.fromString(str)).left.map(_.message)
      }

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

    def decodeNonEmptyStringLikeOrNonEmptySet[T: Order](fromString: NonEmptyString => T): Decoder[NonEmptySet[T]] =
      decodeStringLikeNonEmpty
        .map(NonEmptySet.one(_))
        .or(Decoder.decodeNonEmptySet[NonEmptyString])
        .map(_.map(fromString))

    def decodeStringLikeOrUniqueNonEmptyList[T](fromString: String => T): Decoder[UniqueNonEmptyList[T]] =
      decodeStringLikeOrUniqueNonEmptyListE(str => Right(fromString(str)))

    def decodeStringLikeOrUniqueNonEmptyListE[T](fromString: String => Either[String, T]): Decoder[UniqueNonEmptyList[T]] =
      decodeStringLike.map(str => UniqueNonEmptyList.of(str)).or(decodeUniqueNonEmptyList[String]).emap { uniqueList =>
        val (errorsUniqueList, valuesUniqueList) = uniqueList.foldLeft((UniqueList.empty[String], UniqueList.empty[T])) {
          case ((errors, values), elem) =>
            fromString(elem) match {
              case Right(value) => (errors, values + value)
              case Left(error) => (errors + error, values)
            }
        }
        if (errorsUniqueList.nonEmpty) Left(errorsUniqueList.mkString(","))
        else Right(UniqueNonEmptyList.unsafeFromIterable(valuesUniqueList))
      }

    def decoderStringLikeOrUniqueNonEmptyList[T: Decoder]: Decoder[UniqueNonEmptyList[T]] =
      decodeStringLikeOrUniqueNonEmptyListE { str =>
        Decoder[T].decodeJson(Json.fromString(str)).left.map(_.message)
      }

    def decodeNonEmptyStringLikeOrUniqueNonEmptyList[T](fromString: NonEmptyString => T): Decoder[UniqueNonEmptyList[T]] =
      decodeStringLikeNonEmpty
        .map(UniqueNonEmptyList.of(_))
        .or(DecoderHelpers.decodeUniqueNonEmptyList[NonEmptyString])
        .map(a => UniqueNonEmptyList.unsafeFromIterable(a.toList.map(fromString)))

    def decodeStringLikeOrSet[T : Decoder]: Decoder[Set[T]] = {
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

    def decodeStringLikeOrUniqueList[T: Decoder]: Decoder[UniqueList[T]] = {
      decodeStringLike.map(str => UniqueList.fromIterable(str :: Nil)).or(decodeUniqueList[String]).emap { uniqueList =>
        val (errorsUniqueList, valuesUniqueList) = uniqueList.foldLeft((UniqueList.empty[String], UniqueList.empty[T])) {
          case ((errors, values), elem) =>
            Decoder[T].decodeJson(Json.fromString(elem)) match {
              case Right(value) => (errors, values + value)
              case Left(error) => (errors + error.message, values)
            }
        }
        if (errorsUniqueList.nonEmpty) Left(errorsUniqueList.mkString(","))
        else Right(valuesUniqueList)
      }
    }

    def decodeStringLikeWithSingleVarResolvedInPlace(implicit variableCreator: RuntimeResolvableVariableCreator): Decoder[String] = {
      alwaysRightSingleVariableDecoder(variableCreator)(AlwaysRightConvertible.stringAlwaysRightConvertible)
        .toSyncDecoder
        .emapE {
          case AlreadyResolved(resolved) => Right(resolved)
          case _: ToBeResolved[String] => Left(ValueLevelCreationError(Message(s"Only statically resolved variables can be used")))
        }
        .decoder
    }

    def singleVariableDecoder[T: Convertible](variableCreator: RuntimeResolvableVariableCreator): Decoder[Either[CreationError, RuntimeSingleResolvableVariable[T]]] =
      DecoderHelpers
        .decodeStringLikeNonEmpty
        .map { str => variableCreator.createSingleResolvableVariableFrom(str) }

    def multiVariableDecoder[T: Convertible](variableCreator: RuntimeResolvableVariableCreator): Decoder[Either[CreationError, RuntimeMultiResolvableVariable[T]]] =
      DecoderHelpers
        .decodeStringLikeNonEmpty
        .map { str => variableCreator.createMultiResolvableVariableFrom(str) }

    def alwaysRightSingleVariableDecoder[T: AlwaysRightConvertible](variableCreator: RuntimeResolvableVariableCreator): Decoder[RuntimeSingleResolvableVariable[T]] = {
      SyncDecoderCreator
        .from(singleVariableDecoder[T](variableCreator))
        .emapE {
          _.left.map(error => CoreCreationError.RulesLevelCreationError(Message(error.show)))
        }
        .decoder
    }

    def alwaysRightMultiVariableDecoder[T: AlwaysRightConvertible](variableCreator: RuntimeResolvableVariableCreator): Decoder[RuntimeMultiResolvableVariable[T]] = {
      SyncDecoderCreator
        .from(multiVariableDecoder[T](variableCreator))
        .emapE {
          _.left.map(error => CoreCreationError.RulesLevelCreationError(Message(error.show)))
        }
        .decoder
    }

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
                                               errorCreator: Reason => CoreCreationError = ValueLevelCreationError.apply)
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

    def failed[T](error: CoreCreationError): Decoder[T] = {
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

    def overrideDefaultErrorWith(error: CoreCreationError): DecodingFailure = {
      if (aclCreationError.isDefined) decodingFailure
      else decodingFailure.withMessage(stringify(error))
    }

    def aclCreationError: Option[CoreCreationError] =
      parse(decodingFailure.message).flatMap(Decoder[CoreCreationError].decodeJson).toOption


    def modifyError(updateErrorMessage: String => String): DecodingFailure = {
      aclCreationError
        .map { error =>
          val updatedReason = error.reason match {
            case Message(value) => Message(updateErrorMessage(value))
            case MalformedValue(value) => MalformedValue.fromString(updateErrorMessage(value))
          }
          val updatedError = error match {
            case e: CoreCreationError.GeneralReadonlyrestSettingsError => e.copy(updatedReason)
            case e: CoreCreationError.DefinitionsLevelCreationError => e.copy(updatedReason)
            case e: CoreCreationError.BlocksLevelCreationError => e.copy(updatedReason)
            case e: CoreCreationError.RulesLevelCreationError => e.copy(updatedReason)
            case e: ValueLevelCreationError => e.copy(updatedReason)
            case e: CoreCreationError.AuditingSettingsCreationError => e.copy(updatedReason)
          }
          decodingFailure.withMessage(stringify(updatedError))
        }
        .getOrElse(decodingFailure.withMessage(updateErrorMessage(decodingFailure.message)))
    }

  }

  object DecodingFailureOps {

    import AclCreationErrorCoders._

    def fromError(error: CoreCreationError): DecodingFailure =
      DecodingFailure(Encoder[CoreCreationError].apply(error).noSpaces, Nil)
  }

  object AclCreationErrorCoders {
    private implicit val reasonCodec: Codec[Reason] = codecWithTypeDiscriminator(
      encode = {
        case reason: Message =>
          derivedEncoderWithType[Message]("Message")(reason)
        case reason: MalformedValue =>
          derivedEncoderWithType[MalformedValue]("MalformedValue")(reason)
      },
      decoders = Map(
        "Message" -> derivedDecoderOfSubtype[Reason, Message],
        "MalformedValue" -> derivedDecoderOfSubtype[Reason, MalformedValue],
      )
    )
    implicit val aclCreationErrorCodec: Codec[CoreCreationError] = codecWithTypeDiscriminator(
      encode = {
        case error: CoreCreationError.GeneralReadonlyrestSettingsError =>
          derivedEncoderWithType[CoreCreationError.GeneralReadonlyrestSettingsError]("GeneralReadonlyrestSettingsError")(error)
        case error: CoreCreationError.DefinitionsLevelCreationError =>
          derivedEncoderWithType[CoreCreationError.DefinitionsLevelCreationError]("DefinitionsLevelCreationError")(error)
        case error: CoreCreationError.BlocksLevelCreationError =>
          derivedEncoderWithType[CoreCreationError.BlocksLevelCreationError]("BlocksLevelCreationError")(error)
        case error: CoreCreationError.RulesLevelCreationError =>
          derivedEncoderWithType[CoreCreationError.RulesLevelCreationError]("RulesLevelCreationError")(error)
        case error: ValueLevelCreationError =>
          derivedEncoderWithType[CoreCreationError.ValueLevelCreationError]("ValueLevelCreationError")(error)
        case error: CoreCreationError.AuditingSettingsCreationError =>
          derivedEncoderWithType[CoreCreationError.AuditingSettingsCreationError]("AuditingSettingsCreationError")(error)
      },
      decoders = Map(
        "GeneralReadonlyrestSettingsError" -> derivedDecoderOfSubtype[CoreCreationError, CoreCreationError.GeneralReadonlyrestSettingsError],
        "DefinitionsLevelCreationError" -> derivedDecoderOfSubtype[CoreCreationError, CoreCreationError.DefinitionsLevelCreationError],
        "BlocksLevelCreationError" -> derivedDecoderOfSubtype[CoreCreationError, CoreCreationError.BlocksLevelCreationError],
        "RulesLevelCreationError" -> derivedDecoderOfSubtype[CoreCreationError, CoreCreationError.RulesLevelCreationError],
        "ValueLevelCreationError" -> derivedDecoderOfSubtype[CoreCreationError, CoreCreationError.ValueLevelCreationError],
        "AuditingSettingsCreationError" -> derivedDecoderOfSubtype[CoreCreationError, CoreCreationError.AuditingSettingsCreationError],
      )
    )
    def stringify(error: CoreCreationError): String = Encoder[CoreCreationError].apply(error).noSpaces
  }

  implicit class ACursorOps[C <: ACursor](val value: C) extends AnyVal {
    def downFields(field: String, fields: String*): ACursor = {
      fields.toList.foldLeft(value.downField(field)) {
        case (_: FailedCursor, nextField) => value.downField(nextField)
        case (found: HCursor, _) => found
        case (other, _) => other
      }
    }

    def downFieldsWithKey(field: String, fields: String*): (ACursor, String) = {
      fields.toList.foldLeft((value.downField(field), field)) {
        case ((_: FailedCursor, prevField@_), nextField) => (value.downField(nextField), nextField)
        case ((found: HCursor, foundFiled), _) => (found, foundFiled)
        case ((other, otherField), _) => (other, otherField)
      }
    }

    def downNonEmptyField(name: String): Decoder.Result[NonEmptyString] = {
      import tech.beshu.ror.accesscontrol.factory.decoders.common.nonEmptyStringDecoder
      downFields(name).asWithError[NonEmptyString](s"Field $name cannot be empty")
    }

    def downNonEmptyOptionalField(name: String): Decoder.Result[Option[NonEmptyString]] = {
      import tech.beshu.ror.accesscontrol.factory.decoders.common.nonEmptyStringDecoder
      downFields(name).asWithError[Option[NonEmptyString]](s"Field $name cannot be empty")
    }

    def downFieldAs[T: Decoder](name: String): Decoder.Result[T] = {
      value.downField(name).as[T].adaptError {
        case error: DecodingFailure => error.modifyError(errorMessage => s"Error for field '$name': $errorMessage")
      }
    }

    def withoutKeys(keys: Set[String]): ACursor = {
      value.withFocus(_.mapObject(_.filterKeys(key => !keys.contains(key))))
    }

    def withKeysOnly(keys: Set[String]): ACursor = {
      value.withFocus(_.mapObject(_.filterKeys(key => keys.contains(key))))
    }

    def asWithError[T: Decoder](error: String): Decoder.Result[T] =
      value
        .as[T](implicitly[Decoder[T]])
        .left
        .map(_.overrideDefaultErrorWith(ValueLevelCreationError(Message(error))))
  }
}
