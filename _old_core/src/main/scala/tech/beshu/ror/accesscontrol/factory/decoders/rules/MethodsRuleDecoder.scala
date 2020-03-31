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

import com.softwaremill.sttp.Method
import com.softwaremill.sttp.Method._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.rules.MethodsRule
import tech.beshu.ror.accesscontrol.blocks.rules.MethodsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.MethodsRuleDecoderHelper.methodDecoder
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.accesscontrol.orders._

object MethodsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Method]
    .map(methods => RuleWithVariableUsageDefinition.create(new MethodsRule(Settings(methods))))
)

private object MethodsRuleDecoderHelper {
  implicit val methodDecoder: Decoder[Method] =
    Decoder
      .decodeString
      .map(_.toUpperCase)
      .map(Method.apply)
      .toSyncDecoder
      .emapE {
        case m@(GET | POST | PUT | DELETE | OPTIONS | HEAD) => Right(m)
        case other => Left(RulesLevelCreationError(Message(s"Unknown/unsupported http method: ${other.m}")))
      }
      .decoder
}