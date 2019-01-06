package tech.beshu.ror.acl.factory.decoders

import java.util.regex.Pattern

import tech.beshu.ror.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers

// todo: test compilation fail
object UriRegexRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .valueDecoder(rv => Pattern.compile(rv.value))
    .map(pattern => new UriRegexRule(Settings(pattern)))
)
