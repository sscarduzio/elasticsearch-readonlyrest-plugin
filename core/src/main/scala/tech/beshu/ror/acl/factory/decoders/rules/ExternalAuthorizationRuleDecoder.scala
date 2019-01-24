package tech.beshu.ror.acl.factory.decoders.rules

import tech.beshu.ror.acl.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.acl.blocks.rules.ExternalAuthorizationRule
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields

class ExternalAuthorizationRuleDecoder(authenticationServices: Definitions[ExternalAuthorizationService])
  extends RuleDecoderWithoutAssociatedFields[ExternalAuthorizationRule](
    ???
  )

object ExternalAuthorizationRuleDecoder {

}