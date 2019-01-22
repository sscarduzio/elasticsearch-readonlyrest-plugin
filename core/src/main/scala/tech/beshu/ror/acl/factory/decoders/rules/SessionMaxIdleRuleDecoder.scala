package tech.beshu.ror.acl.factory.decoders.rules

import java.time.Clock

import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.common
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.UuidProvider
import tech.beshu.ror.acl.utils.CirceOps._

class SessionMaxIdleRuleDecoder(implicit clock: Clock, uuidProvider: UuidProvider)
  extends RuleDecoderWithoutAssociatedFields(
    common
      .positiveFiniteDurationDecoder
      .map(maxIdle => new SessionMaxIdleRule(Settings(maxIdle)))
      .mapError(RulesLevelCreationError.apply)
  )
