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
package tech.beshu.ror.acl.factory.decoders.rules

import java.util.regex.Pattern

import cats.implicits._
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.blocks.Variable.ResolvedValue._

import scala.util.Try
import tech.beshu.ror.acl.utils.CirceOps._

object UriRegexRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .valueDecoder { rv =>
      Try(Pattern.compile(rv.value))
        .toEither
        .left
        .map(_ => Value.ConvertError(rv, "Cannot compile pattern"))
    }
    .toSyncDecoder
    .emapE {
      case Right(pattern) => Right(new UriRegexRule(Settings(pattern)))
      case Left(error) => Left(RulesLevelCreationError(Message(s"${error.msg}: ${error.resolvedValue.show}")))
    }
    .decoder
)
