package tech.beshu.ror.acl.factory.decoders.rules

import tech.beshu.ror.acl.blocks.definitions.JwtDef
import tech.beshu.ror.acl.blocks.rules.JwtAuthRule
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.common._

class JwtAuthRuleDecoder(jwtDefinitions: Definitions[JwtDef]) extends RuleDecoderWithoutAssociatedFields[JwtAuthRule](
  ???
)

private object JwtAuthRuleDecoder {

}