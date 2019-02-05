package tech.beshu.ror.acl.factory.decoders.rules

import tech.beshu.ror.acl.aDomain.Action
import tech.beshu.ror.acl.blocks.rules.ActionsRule
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers

object ActionsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySet(Action.apply).map(actions => new ActionsRule(ActionsRule.Settings(actions)))
)
