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
import tech.beshu.ror.accesscontrol.blocks.rules.http.UriRegexRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders.patternOrder
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, DecoderOps}
import tech.beshu.ror.implicits.*

import java.util.regex.Pattern
import scala.util.Try

class UriRegexRuleDecoder(variableCreator: RuntimeResolvableVariableCreator)
  extends RuleBaseDecoderWithoutAssociatedFields[UriRegexRule] {

  override protected def decoder: Decoder[RuleDefinition[UriRegexRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[Pattern]]
      .map(patterns => RuleDefinition.create(new UriRegexRule(UriRegexRule.Settings(patterns))))
  }

  implicit val patternConvertible: Convertible[Pattern] = new Convertible[Pattern] {
    override def convert: String => Either[Convertible.ConvertError, Pattern] = str => {
      Try(Pattern.compile(str))
        .toEither
        .left
        .map(_ => ConvertError(s"Cannot compile pattern: ${str.show}"))
    }
  }

  implicit val patternDecoder: Decoder[RuntimeMultiResolvableVariable[Pattern]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        variableCreator
          .createMultiResolvableVariableFrom[Pattern](str)
          .left.map(error => RulesLevelCreationError(Message(error.show)))
      }
      .decoder
}
