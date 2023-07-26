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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.http

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.http.XForwardedForRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver
import tech.beshu.ror.accesscontrol.orders.addressOrder
import tech.beshu.ror.accesscontrol.factory.decoders.common._

class XForwardedForRuleDecoder(variableCreator: RuntimeResolvableVariableCreator)
  extends RuleBaseDecoderWithoutAssociatedFields[XForwardedForRule] {

  private implicit val _variableCreator: RuntimeResolvableVariableCreator = variableCreator

  override protected def decoder: Decoder[RuleDefinition[XForwardedForRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[Address]]
      .map(addresses => RuleDefinition.create(
        new XForwardedForRule(XForwardedForRule.Settings(addresses), new Ip4sBasedHostnameResolver)
      ))
  }
}
