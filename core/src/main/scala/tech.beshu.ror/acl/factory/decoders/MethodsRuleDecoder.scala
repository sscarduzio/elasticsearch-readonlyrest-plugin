package tech.beshu.ror.acl.factory.decoders

import com.softwaremill.sttp.Method
import com.softwaremill.sttp.Method._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.MethodsRule
import tech.beshu.ror.acl.blocks.rules.MethodsRule.Settings
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import MethodsRuleDecoderHelper.methodDecoder
import tech.beshu.ror.commons.orders._

object MethodsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Method]
    .map(methods => new MethodsRule(Settings(methods)))
)

private object MethodsRuleDecoderHelper {
  implicit val methodDecoder: Decoder[Method] =
    Decoder
      .decodeString
      .map(_.toUpperCase)
      .map(Method.apply)
      .emap {
        case m@(GET | POST | PUT | DELETE | OPTIONS | HEAD) => Right(m)
        case other => Left(s"Unknown/unsupported http method: ${other.m}")
      }
}