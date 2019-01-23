package tech.beshu.ror.acl.factory.decoders.rules

import tech.beshu.ror.acl.blocks.rules.ApiKeysRule
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.aDomain.ApiKey
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.orders._

object ApiKeysRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySet(ApiKey.apply).map(apiKeys => new ApiKeysRule(ApiKeysRule.Settings(apiKeys)))
)
