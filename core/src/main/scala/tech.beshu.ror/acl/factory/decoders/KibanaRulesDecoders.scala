package tech.beshu.ror.acl.factory.decoders

import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.rules.{KibanaHideAppsRule, KibanaIndexRule}
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.commons.aDomain.{IndexName, KibanaApp}
import tech.beshu.ror.commons.orders._

object KibanaHideAppsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySet(KibanaApp.apply).map(apps => new KibanaHideAppsRule(Settings(apps)))
)

object KibanaIndexRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLike
    .map(e => Value.fromString(e, rv => IndexName(rv.value)))
    .map(index => new KibanaIndexRule(KibanaIndexRule.Settings(index)))
)
