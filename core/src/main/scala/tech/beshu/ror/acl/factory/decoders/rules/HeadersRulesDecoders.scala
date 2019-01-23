package tech.beshu.ror.acl.factory.decoders.rules

import tech.beshu.ror.acl.blocks.rules.{HeadersAndRule, HeadersOrRule}
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.HeadersHelper.headerFromString
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.aDomain.Header.Name
import tech.beshu.ror.acl.orders._

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