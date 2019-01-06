package tech.beshu.ror.acl.factory.decoders

import tech.beshu.ror.acl.blocks.rules.ActionsRule
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.commons.aDomain.Action
import tech.beshu.ror.commons.orders.actionOrder

object ActionsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySet(Action.apply).map(actions => new ActionsRule(ActionsRule.Settings(actions)))
)
