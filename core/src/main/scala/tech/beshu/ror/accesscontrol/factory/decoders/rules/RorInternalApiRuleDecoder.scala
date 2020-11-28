/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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
