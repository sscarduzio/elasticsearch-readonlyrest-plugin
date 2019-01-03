package tech.beshu.ror.acl.factory.decoders

import io.circe.Decoder
import squants.information.Bytes
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule.Settings
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields

object MaxBodyLengthRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  Decoder
    .decodeLong
    .emap { value =>
      if (value >= 0) Right(Bytes(value))
      else Left(s"Invalid max body length: $value")
    }
    .map(maxBodyLength => new MaxBodyLengthRule(Settings(maxBodyLength)))
)
