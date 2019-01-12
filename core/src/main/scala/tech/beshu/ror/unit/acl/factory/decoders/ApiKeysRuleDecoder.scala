package tech.beshu.ror.unit.acl.factory.decoders

import tech.beshu.ror.unit.acl.blocks.rules.ApiKeysRule
import tech.beshu.ror.unit.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.unit.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.commons.aDomain.ApiKey
import tech.beshu.ror.commons.orders.apiKeyOrder

object ApiKeysRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySet(ApiKey.apply).map(apiKeys => new ApiKeysRule(ApiKeysRule.Settings(apiKeys)))
)
