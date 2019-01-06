package tech.beshu.ror.acl.factory.decoders

import tech.beshu.ror.acl.blocks.rules.{HeadersAndRule, HeadersOrRule}
import tech.beshu.ror.acl.factory.decoders.HeadersHelper.headerFromString
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.orders._

object HeadersAndRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySetE(headerFromString).map(headers => new HeadersAndRule(HeadersAndRule.Settings(headers)))
)

object HeadersOrRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySetE(headerFromString).map(headers => new HeadersOrRule(HeadersOrRule.Settings(headers)))
)

private object HeadersHelper {
  def headerFromString(value: String): Either[String, Header] = value.split(":").toList match {
    case hName :: hValue :: Nil => Right(Header(Name(hName), hValue))
    case _ => Left(s"Cannot convert $value to header")
  }
}