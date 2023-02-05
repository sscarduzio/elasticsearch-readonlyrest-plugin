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
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.LocalHostsRule
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.{HostsRule, LocalHostsRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.HostRulesDecodersHelper._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.{RuleBaseDecoderWithAssociatedFields, RuleBaseDecoderWithoutAssociatedFields}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver

object HostsRuleDecoder
  extends RuleBaseDecoderWithAssociatedFields[HostsRule, Boolean] {

  override def ruleDecoderCreator: Boolean => Decoder[RuleDefinition[HostsRule]] =
    acceptXForwardedFor =>
      DecoderHelpers
        .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[Address]]
        .map(nes => RuleDefinition.create(
          new HostsRule(HostsRule.Settings(nes, acceptXForwardedFor), new Ip4sBasedHostnameResolver)
        ))

  override val associatedFields: Set[String] = Set("accept_x-forwarded-for_header")

  override val associatedFieldsDecoder: Decoder[Boolean] =
    Decoder.instance(_.downField("accept_x-forwarded-for_header").as[Boolean])
      .or(Decoder.const(defaultAcceptForwardedForHeader))
}

class LocalHostsRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[LocalHostsRule] {

  override protected def decoder: Decoder[RuleDefinition[LocalHostsRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[Address]]
      .map(addresses => RuleDefinition.create(
        new LocalHostsRule(LocalHostsRule.Settings(addresses), new Ip4sBasedHostnameResolver)
      ))
  }
}

private object HostRulesDecodersHelper {

  val defaultAcceptForwardedForHeader = false
}