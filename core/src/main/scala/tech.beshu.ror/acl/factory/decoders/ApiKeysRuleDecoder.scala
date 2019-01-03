package tech.beshu.ror.acl.factory.decoders

import tech.beshu.ror.acl.blocks.rules.ApiKeysRule
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderOps
import tech.beshu.ror.commons.aDomain.ApiKey
import tech.beshu.ror.commons.orders.apiKeyOrder

object ApiKeysRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderOps.decodeStringLikeOrNonEmptySet(ApiKey.apply).map(apiKeys => new ApiKeysRule(ApiKeysRule.Settings(apiKeys)))
)
