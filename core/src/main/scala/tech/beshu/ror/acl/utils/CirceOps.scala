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

import cats.data.NonEmptySet
import cats.implicits._
import cats.{Applicative, Order}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.CursorOp.DownField
import io.circe._
import io.circe.generic.extras
import io.circe.generic.extras.Configuration
import io.circe.parser._
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService
import tech.beshu.ror.acl.blocks.definitions.{ExternalAuthenticationService, ImpersonatorDef, JwtDef, ProxyAuth, RorKbnDef}
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariableCreator.{CreationError, createMultiResolvableVariableFrom, createSingleResolvableVariableFrom}
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeSingleResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.acl.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeSingleResolvableVariable}
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.{DefinitionsLevelCreationError, Reason, ValueLevelCreationError}
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.authenticationRuleDecoderBy
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult._

import scala.collection.SortedSet
import scala.language.higherKinds
import scala.language.existentials

object CirceOps {

  object DecoderHelpers {
    val decodeStringLike: Decoder[String] = Decoder.decodeString.or(Decoder.decodeInt.map(_.show))

    implicit val decodeStringLikeNonEmpty: Decoder[NonEmptyString] = decodeStringLike.emap(NonEmptyString.from)

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

    val decodeStringLikeWithSingleVarResolvedInPlace: Decoder[String] = {
      alwaysRightSingleVariableDecoder(AlwaysRightConvertible.stringAlwaysRightConvertible)
          .toSyncDecoder
          .emapE {
            case AlreadyResolved(resolved) => Right(resolved)
            case _: ToBeResolved[String] => Left(ValueLevelCreationError(Message(s"Only statically resolved variables can be used")))
          }
          .decoder
    }

    def singleVariableDecoder[T : Convertible]: Decoder[Either[CreationError, RuntimeSingleResolvableVariable[T]]] =
      DecoderHelpers
        .decodeStringLikeNonEmpty
        .map { str => createSingleResolvableVariableFrom(str) }

    def multiVariableDecoder[T : Convertible]: Decoder[Either[CreationError, RuntimeMultiResolvableVariable[T]]] =
      DecoderHelpers
        .decodeStringLikeNonEmpty
        .map { str => createMultiResolvableVariableFrom(str) }

    def alwaysRightSingleVariableDecoder[T : AlwaysRightConvertible]: Decoder[RuntimeSingleResolvableVariable[T]] =
      SyncDecoderCreator
        .from(singleVariableDecoder[T])
        .emapE {
          _.left.map(error => AclCreationError.RulesLevelCreationError(Message(error.show)))
        }
        .decoder

    def alwaysRightMultiVariableDecoder[T : AlwaysRightConvertible]: Decoder[RuntimeMultiResolvableVariable[T]] =
      SyncDecoderCreator
        .from(multiVariableDecoder[T])
        .emapE {
          _.left.map(error => AclCreationError.RulesLevelCreationError(Message(error.show)))
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

    def withoutKeys(keys: Set[String]): ACursor = {
      value.withFocus(_.mapObject(_.filterKeys(key => !keys.contains(key))))
    }
  }

  implicit class ACursorOps(val value: ACursor) extends AnyVal {

    def asWithError[T : Decoder](error: String): Decoder.Result[T] =
      value
        .as[T](implicitly[Decoder[T]])
        .left
        .map(_.overrideDefaultErrorWith(ValueLevelCreationError(Message(error))))

    def tryDecodeAuthRule(username: User.Id)
                         (implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                          authProxyDefinitions: Definitions[ProxyAuth],
                          jwtDefinitions: Definitions[JwtDef],
                          ldapDefinitions: Definitions[LdapService],
                          rorKbnDefinitions: Definitions[RorKbnDef],
                          impersonatorDefs: Option[Definitions[ImpersonatorDef]]) = {
      value.keys.map(_.toList) match {
        case Some(key :: Nil) =>
          val decoder = authenticationRuleDecoderBy(
            Rule.Name(key),
            authenticationServiceDefinitions,
            authProxyDefinitions,
            jwtDefinitions,
            ldapDefinitions,
            rorKbnDefinitions,
            impersonatorDefs
          ) match {
            case Some(authRuleDecoder) => authRuleDecoder
            case None => DecoderHelpers.failed[AuthenticationRule](
              DefinitionsLevelCreationError(Message(s"Rule $key is not authentication rule"))
            )
          }
          decoder.tryDecode(value.downField(key))
            .left.map(_ => Message(s"Cannot parse '$key' rule declared in user '${username.show}' definition"))
        case Some(keys) =>
          Left(Message(s"Only one authentication should be defined for user ['${username.show}']. Found ${keys.mkString(", ")}"))
        case None | Some(Nil) =>
          Left(Message(s"No authentication method defined for user ['${username.show}']"))
      }
    }
  }

}
