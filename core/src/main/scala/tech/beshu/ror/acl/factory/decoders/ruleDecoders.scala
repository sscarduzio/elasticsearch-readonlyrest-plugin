package tech.beshu.ror.acl.factory.decoders

import java.time.Clock

import cats.data.NonEmptySet
import io.circe.CursorOp.DownField
import io.circe.Decoder.Result
import io.circe._
import tech.beshu.ror.acl.blocks.definitions.ProxyAuthDefinitions
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.rules._
import tech.beshu.ror.acl.utils.CirceOps.DecodingFailureOps
import tech.beshu.ror.acl.utils.UuidProvider

import scala.language.implicitConversions

object ruleDecoders {

  sealed abstract class RuleDecoder[T <: Rule](val associatedFields: Set[String]) extends Decoder[T] {
    def decode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[T] = {
      doDecode(value, associatedFieldsJson)
        .left
        .map(df => df.overrideDefaultErrorWith(RulesLevelCreationError {
          value.top match {
            case Some(json) =>
              MalformedValue(json)
            case None =>
              val ruleName = df.history.headOption.collect { case df: DownField => df.k }.getOrElse("")
              Message(s"Malformed rule $ruleName")
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

  implicit def ruleDecoderBy(name: Rule.Name, definitions: Definitions)
                            (implicit clock: Clock, uuidProvider: UuidProvider): Option[RuleDecoder[_ <: Rule]] =
    name match {
      case ActionsRule.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.name => Some(ApiKeysRuleDecoder)
      case FieldsRule.name => Some(FieldsRuleDecoder)
      case FilterRule.name => Some(FilterRuleDecoder)
      case GroupsRule.name => Some(new GroupsRuleDecoder(definitions.users))
      case HeadersAndRule.name => Some(HeadersAndRuleDecoder)
      case HeadersOrRule.name => Some(HeadersOrRuleDecoder)
      case HostsRule.name => Some(HostsRuleDecoder)
      case IndicesRule.name => Some(IndicesRuleDecoders)
      case KibanaAccessRule.name => Some(KibanaAccessRuleDecoder)
      case KibanaHideAppsRule.name => Some(KibanaHideAppsRuleDecoder)
      case KibanaIndexRule.name => Some(KibanaIndexRuleDecoder)
      case LocalHostsRule.name => Some(LocalHostsRuleDecoder)
      case MaxBodyLengthRule.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.name => Some(MethodsRuleDecoder)
      case SessionMaxIdleRule.name => Some(new SessionMaxIdleRuleDecoder)
      case UriRegexRule.name => Some(UriRegexRuleDecoder)
      case UsersRule.name => Some(UsersRuleDecoder)
      case XForwardedForRule.name => Some(XForwardedForRuleDecoder)
      case _ => authenticationRuleDecoderBy(name, definitions.proxies)
    }

  def authenticationRuleDecoderBy(name: Rule.Name,
                                  authProxyDefinitions: ProxyAuthDefinitions): Option[RuleDecoder[_ <: AuthenticationRule]] = {
    name match {
      case AuthKeyRule.name => Some(AuthKeyRuleDecoder)
      case AuthKeySha1Rule.name => Some(AuthKeySha1RuleDecoder)
      case AuthKeySha256Rule.name => Some(AuthKeySha256RuleDecoder)
      case AuthKeySha512Rule.name => Some(AuthKeySha512RuleDecoder)
      case AuthKeyUnixRule.name => Some(AuthKeyUnixRuleDecoder)
      case ProxyAuthRule.name => Some(new ProxyAuthRuleDecoder(authProxyDefinitions))
      case _ => None
    }
  }
}
