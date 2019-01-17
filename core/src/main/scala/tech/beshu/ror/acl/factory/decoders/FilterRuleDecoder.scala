package tech.beshu.ror.acl.factory.decoders

import tech.beshu.ror.commons.aDomain.Filter
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.rules.FilterRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, _}

object FilterRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLike
    .map(e => Value.fromString(e, rv => Right(Filter(rv.value))))
    .emapE {
      case Right(filter) => Right(new FilterRule(FilterRule.Settings(filter)))
      case Left(error) => Left(RulesLevelCreationError(Message(error.msg)))
    }
)
