package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import io.circe.Decoder.Result
import io.circe._
import tech.beshu.ror.acl.blocks.rules.{AuthKeyRule, HostsRule, Rule}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.utils.CirceOps.DecodingFailureOps

import scala.language.implicitConversions

object ruleDecoders {

  sealed abstract class RuleDecoder[T <: Rule](val associatedFields: Set[String]) extends Decoder[T] {
    def decode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[T]
  }

  object RuleDecoder {
    private [decoders] class RuleDecoderWithoutAssociatedFields[T <: Rule](decoder: Decoder[T])
      extends RuleDecoder[T](Set.empty) {
      override def decode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = decoder.tryDecode(value)
      override def apply(c: HCursor): Result[T] = decoder.apply(c)
    }

    private [decoders] class RuleDecoderWithAssociatedFields[T <: Rule, S](ruleDecoderCreator: S => Decoder[T],
                                                                               associatedFields: NonEmptySet[String],
                                                                               associatedFieldsDecoder: Decoder[S])
      extends RuleDecoder[T](associatedFields.toSortedSet) {
      override def decode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = {
        for {
          decodedAssociatedFields <- associatedFieldsDecoder.tryDecode(associatedFieldsJson)
          rule <- ruleDecoderCreator(decodedAssociatedFields).tryDecode(value)
        } yield rule
      }

      override def apply(c: HCursor): Result[T] =
        Left(DecodingFailureOps.fromError(RulesLevelCreationError("Rule with associated fields decoding failed")))
    }

    private[decoders] def failed[T <: Rule](error: RulesLevelCreationError): RuleDecoder[T] =
      new RuleDecoder[T](Set.empty) {
        private val decodingFailureResult = Left(DecodingFailureOps.fromError(error))
        override def decode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = decodingFailureResult
        override def apply(c: HCursor): Result[T] = decodingFailureResult
      }
  }

  implicit def ruleDecoderBy(name: String): Option[RuleDecoder[_ <: Rule]] = Rule.Name(name) match {
    case AuthKeyRule.name => Some(AuthKeyRuleDecoder)
    case HostsRule.name => Some(HostsRuleDecoder)
    case _ => None
  }

}
