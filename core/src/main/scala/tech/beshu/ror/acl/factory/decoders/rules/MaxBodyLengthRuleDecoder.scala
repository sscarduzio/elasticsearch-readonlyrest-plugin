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

import io.circe.Decoder
import squants.information.Bytes
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule.Settings
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps._

object MaxBodyLengthRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  Decoder
    .decodeLong
    .toSyncDecoder
    .emapE { value =>
      if (value >= 0) Right(Bytes(value))
      else Left(RulesLevelCreationError(Message(s"Invalid max body length: $value")))
    }
    .map(maxBodyLength => new MaxBodyLengthRule(Settings(maxBodyLength)))
    .decoder
)
