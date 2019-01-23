package tech.beshu.ror.acl.factory.decoders.rules

import io.circe.Decoder
import squants.information.Bytes
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule.Settings
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps._

object MaxBodyLengthRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  Decoder
    .decodeLong
    .emapE { value =>
      if (value >= 0) Right(Bytes(value))
      else Left(RulesLevelCreationError(Message(s"Invalid max body length: $value")))
    }
    .map(maxBodyLength => new MaxBodyLengthRule(Settings(maxBodyLength)))
)
