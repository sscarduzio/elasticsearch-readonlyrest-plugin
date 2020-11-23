package tech.beshu.ror.accesscontrol.factory.decoders.rules

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.{InternalApiAccess, Settings}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.domain.{RorAuditIndexTemplate, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._

class RorInternalApiRuleDecoder(configurationIndex: RorConfigurationIndex,
                                indexAuditTemplate: Option[RorAuditIndexTemplate])
  extends RuleDecoderWithoutAssociatedFields(
    Decoder
      .decodeString
      .map(_.toLowerCase)
      .toSyncDecoder
      .emapE[InternalApiAccess] {
        case "allow" => Right(InternalApiAccess.Allow)
        case "forbid" => Right(InternalApiAccess.Forbid)
        case unknown => Left(AclCreationError.RulesLevelCreationError(Message(s"Unknown ROR internal access type '$unknown'. Possible options: 'allow', 'forbid'")))
      }
      .map { access =>
        RuleWithVariableUsageDefinition.create {
          new RorInternalApiRule(Settings(access, configurationIndex, indexAuditTemplate))
        }
      }
      .decoder
  )
