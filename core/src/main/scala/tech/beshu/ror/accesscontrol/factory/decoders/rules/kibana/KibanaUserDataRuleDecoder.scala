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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.kibana

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaUserDataRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, KibanaAccess, KibanaApp, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders.kibanaAppOrder
import tech.beshu.ror.accesscontrol.utils.CirceOps._

class KibanaUserDataRuleDecoder(configurationIndex: RorConfigurationIndex)
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaUserDataRule] {

  private implicit val uniqueNonEmptyListOfKibanaAppsDecoder: Decoder[Set[KibanaApp]] =
    DecoderHelpers.decodeStringLikeOrSet[KibanaApp]

  override protected def decoder: Decoder[Block.RuleDefinition[KibanaUserDataRule]] = {
    Decoder
      .instance { c =>
        for {
          access <- c.downField("access").as[KibanaAccess]
          kibanaIndex <- c.downField("kibana_index").as[Option[RuntimeSingleResolvableVariable[IndexName.Kibana]]]
          kibanaTemplateIndex <- c.downField("kibana_template_index").as[Option[RuntimeSingleResolvableVariable[IndexName.Kibana]]]
          appsToHide <- c.downField("hide_apps").as[Option[Set[KibanaApp]]]
        } yield new KibanaUserDataRule(KibanaUserDataRule.Settings(
          access,
          kibanaIndex.getOrElse(RuntimeSingleResolvableVariable.AlreadyResolved(ClusterIndexName.Local.kibanaDefault)),
          kibanaTemplateIndex,
          appsToHide.getOrElse(Set.empty),
          configurationIndex
        ))
      }
      .map(RuleDefinition.create[KibanaUserDataRule](_))
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .decoder
  }
}
