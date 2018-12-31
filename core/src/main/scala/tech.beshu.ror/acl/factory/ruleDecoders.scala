package tech.beshu.ror.acl.factory

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder.Result
import io.circe._
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Variable.ResolvedValue
import tech.beshu.ror.acl.blocks.rules.{AuthKeyRule, BasicAuthenticationRule, HostsRule, Rule}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RuleInstantiatingError
import tech.beshu.ror.acl.factory.ruleDecoders.RuleDecoder.{RuleDecoderWithAssociatedFields, RuleDecoderWithoutAssociatedFields}
import tech.beshu.ror.acl.utils.CirceOps.{DecoderOps, DecodingFailureOps}
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.orders._

import scala.collection.immutable.SortedSet
import scala.language.implicitConversions

object ruleDecoders {

  sealed abstract class RuleDecoder[T <: Rule](val associatedFields: Set[String]) extends Decoder[T] {
    def decode(value: ACursor, associatedFieldsJson: Json): Decoder.Result[T]
  }

  object RuleDecoder {
    private [ruleDecoders] class RuleDecoderWithoutAssociatedFields[T <: Rule](decoder: Decoder[T])
      extends RuleDecoder[T](Set.empty) {
      override def decode(value: ACursor, associatedFieldsJson: Json): Result[T] = decoder.tryDecode(value)
      override def apply(c: HCursor): Result[T] = decoder.apply(c)
    }

    private [ruleDecoders] class RuleDecoderWithAssociatedFields[T <: Rule, S](ruleDecoderCreator: S => Decoder[T],
                                                                               associatedFields: NonEmptySet[String],
                                                                               associatedFieldsDecoder: Decoder[S])
      extends RuleDecoder[T](associatedFields.toSortedSet) {
      override def decode(value: ACursor, associatedFieldsJson: Json): Result[T] = {
        for {
          decodedAssociatedFields <- associatedFieldsDecoder.decodeJson(associatedFieldsJson)
          rule <- ruleDecoderCreator(decodedAssociatedFields).tryDecode(value)
        } yield rule
      }

      override def apply(c: HCursor): Result[T] =
        Left(DecodingFailureOps.fromError(RuleInstantiatingError("Rule with associated fields decoding failed")))
    }

    private[ruleDecoders] def failed[T <: Rule](error: RuleInstantiatingError): RuleDecoder[T] =
      new RuleDecoder[T](Set.empty) {
        private val decodingFailureResult = Left(DecodingFailureOps.fromError(error))
        override def decode(value: ACursor, associatedFieldsJson: Json): Result[T] = decodingFailureResult
        override def apply(c: HCursor): Result[T] = decodingFailureResult
      }
  }

  implicit def ruleDecoder(name: String): RuleDecoder[_ <: Rule] = Rule.Name(name) match {
    case AuthKeyRule.name => authKeyRuleDecoder
    case HostsRule.name => hostsRuleDecoder
    case unknown => RuleDecoder.failed[Rule](RuleInstantiatingError(s"Unknown rule defined: [${unknown.show}]"))
  }

  private implicit val authKeyRuleDecoder: RuleDecoder[AuthKeyRule] = new RuleDecoderWithoutAssociatedFields(
    DecoderOps.decodeStringOrNumber.map(BasicAuthenticationRule.Settings.apply).map(new AuthKeyRule(_))
  )

  private implicit val hostsRuleDecoder: RuleDecoder[HostsRule] = {
    def valueDecoder[T](convert: ResolvedValue => T): Decoder[Value[T]] =
      DecoderOps.decodeStringOrNumber.map(str => Value.fromString(str, convert))

    implicit val addressValueDecoder: Decoder[Value[Address]] = valueDecoder(rv => Address(rv.value))
    new RuleDecoderWithAssociatedFields[HostsRule, Boolean](
      ruleDecoderCreator = acceptXForwardedFor =>
        Decoder
          .decodeList[Value[Address]]
          .map(v => SortedSet(v: _*))
          .map(NonEmptySet.fromSet)
          .flatMap {
            case Some(nes) =>
              Decoder.const(new HostsRule(HostsRule.Settings(nes, acceptXForwardedFor)))
            case None =>
              Decoder.failed(DecodingFailureOps.fromError(RuleInstantiatingError(s"At least one host in ${HostsRule.name} rule should be defined")))
          },
      associatedFields = NonEmptySet.one("accept_x-forwarded-for_header"),
      associatedFieldsDecoder = Decoder.instance(_.downField("accept_x-forwarded-for_header").as[Boolean])
    )
  }
}
