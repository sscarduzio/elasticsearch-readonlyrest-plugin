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

import sttp.model.Method
import sttp.model.Method._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.http.MethodsRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.MethodsRule.Settings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.http.MethodsRuleDecoderHelper.methodDecoder
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

object MethodsRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[MethodsRule] {

  override protected def decoder: Decoder[RuleDefinition[MethodsRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[Method]
      .map(methods => RuleDefinition.create(new MethodsRule(Settings(methods))))
  }
}

private object MethodsRuleDecoderHelper {
  implicit val methodDecoder: Decoder[Method] =
    Decoder
      .decodeString
      .map(_.toUpperCase)
      .map(Method.apply)
      .toSyncDecoder
      .emapE {
        case m@(GET | POST | PUT | DELETE | OPTIONS | HEAD) => Right(m)
        case other => Left(RulesLevelCreationError(Message(s"Unknown/unsupported http method: ${other.method}")))
      }
      .decoder
}