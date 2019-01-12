package tech.beshu.ror.unit.acl.factory.decoders

import java.time.Clock

import cats.data.NonEmptySet
import io.circe.Decoder.Result
import io.circe._
import tech.beshu.ror.unit.acl.blocks.rules._
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.utils.CirceOps.DecodingFailureOps
import tech.beshu.ror.unit.acl.utils.UuidProvider

import scala.language.implicitConversions

object ruleDecoders {

  sealed abstract class RuleDecoder[T <: Rule](val associatedFields: Set[String]) extends Decoder[T] {
    def decode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[T] = {
      doDecode(value, associatedFieldsJson)
        .left
        .map(_.overrideDefaultErrorWith(RulesLevelCreationError {
          value.top match {
            case Some(json) => MalformedValue(json)
            case None => Message("Malformed rule")
          }
        }))
    }

    protected def doDecode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[T]
  }

  object RuleDecoder {
    private [decoders] class RuleDecoderWithoutAssociatedFields[T <: Rule](decoder: Decoder[T])
      extends RuleDecoder[T](Set.empty) {
      override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = decoder.tryDecode(value)
      override def apply(c: HCursor): Result[T] = decoder.apply(c)
    }

    private [decoders] class RuleDecoderWithAssociatedFields[T <: Rule, S](ruleDecoderCreator: S => Decoder[T],
                                                                               associatedFields: NonEmptySet[String],
                                                                               associatedFieldsDecoder: Decoder[S])
      extends RuleDecoder[T](associatedFields.toSortedSet) {
      override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = {
        for {
          decodedAssociatedFields <- associatedFieldsDecoder.tryDecode(associatedFieldsJson)
          rule <- ruleDecoderCreator(decodedAssociatedFields).tryDecode(value)
        } yield rule
      }

      override def apply(c: HCursor): Result[T] =
        Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message("Rule with associated fields decoding failed"))))
    }

    private[decoders] def failed[T <: Rule](error: RulesLevelCreationError): RuleDecoder[T] =
      new RuleDecoder[T](Set.empty) {
        private val decodingFailureResult = Left(DecodingFailureOps.fromError(error))
        override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = decodingFailureResult
        override def apply(c: HCursor): Result[T] = decodingFailureResult
      }
  }

  implicit def ruleDecoderBy(name: String, authProxies: Set[ProxyAuth])
                            (implicit clock: Clock, uuidProvider: UuidProvider): Option[RuleDecoder[_ <: Rule]] =
    Rule.Name(name) match {
      case ActionsRule.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.name => Some(ApiKeysRuleDecoder)
      case AuthKeyRule.name => Some(AuthKeyRuleDecoder)
      case AuthKeySha1Rule.name => Some(AuthKeySha1RuleDecoder)
      case AuthKeySha256Rule.name => Some(AuthKeySha256RuleDecoder)
      case AuthKeySha512Rule.name => Some(AuthKeySha512RuleDecoder)
      case AuthKeyUnixRule.name => Some(AuthKeyUnixRuleDecoder)
      case FieldsRule.name => Some(FieldsRuleDecoder)
      case HeadersAndRule.name => Some(HeadersAndRuleDecoder)
      case HeadersOrRule.name => Some(HeadersOrRuleDecoder)
      case HostsRule.name => Some(HostsRuleDecoder)
      case KibanaHideAppsRule.name => Some(KibanaHideAppsRuleDecoder)
      case KibanaIndexRule.name => Some(KibanaIndexRuleDecoder)
      case LocalHostsRule.name => Some(LocalHostsRuleDecoder)
      case MaxBodyLengthRule.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.name => Some(MethodsRuleDecoder)
      case ProxyAuthRule.name => Some(new ProxyAuthRuleDecoder(authProxies))
      case SessionMaxIdleRule.name => Some(new SessionMaxIdleRuleDecoder)
      case UriRegexRule.name => Some(UriRegexRuleDecoder)
      case UsersRule.name => Some(UsersRuleDecoder)
      case XForwardedForRule.name => Some(XForwardedForRuleDecoder)
      case _ => None
    }
}
