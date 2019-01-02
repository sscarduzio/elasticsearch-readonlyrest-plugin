package tech.beshu.ror.acl.factory.decoders

import tech.beshu.ror.acl.blocks.rules.{AuthKeyRule, BasicAuthenticationRule}
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderOps

object AuthKeyRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderOps.decodeStringOrNumber.map(BasicAuthenticationRule.Settings.apply).map(new AuthKeyRule(_))
)