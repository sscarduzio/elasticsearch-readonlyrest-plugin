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

import java.util.regex.Pattern

import cats.implicits._
import tech.beshu.ror.accesscontrol.blocks.rules.UriRegexRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.show.logs._
import UriRegexRuleDecoder.patternConvertible

import scala.util.Try

class UriRegexRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .singleVariableDecoder[Pattern]
    .toSyncDecoder
    .emapE {
      case Right(pattern) => Right(new UriRegexRule(UriRegexRule.Settings(pattern)))
      case Left(error) => Left(RulesLevelCreationError(Message(error.show)))
    }
    .decoder
)

object UriRegexRuleDecoder {
  implicit val patternConvertible: Convertible[Pattern] = new Convertible[Pattern] {
    override def convert: String => Either[Convertible.ConvertError, Pattern] = str => {
      Try(Pattern.compile(str))
        .toEither
        .left
        .map(_ => ConvertError(s"Cannot compile pattern: $str"))
    }
  }
}