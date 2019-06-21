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

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.{BaseSpecializedIndicesRule, IndicesRule, RepositoriesRule, SnapshotsRule}
import tech.beshu.ror.acl.blocks.variables.{AlreadyResolved, RuntimeResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.IndicesDecodersHelper._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.domain.IndexName
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.utils.EnvVarsProvider

class IndicesRuleDecoders(implicit provider: EnvVarsProvider) extends RuleDecoderWithoutAssociatedFields[IndicesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeResolvableVariable[IndexName]]
    .map(indices => new IndicesRule(IndicesRule.Settings(indices)))
)

class SnapshotsRuleDecoder(implicit provider: EnvVarsProvider) extends RuleDecoderWithoutAssociatedFields[SnapshotsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeResolvableVariable[IndexName]]
    .toSyncDecoder
    .emapE { indices =>
      if(indices.contains(AlreadyResolved(IndexName.all)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(indices.contains(AlreadyResolved(IndexName.wildcard)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => new SnapshotsRule(BaseSpecializedIndicesRule.Settings(indices)))
    .decoder
)

class RepositoriesRuleDecoder(implicit provider: EnvVarsProvider) extends RuleDecoderWithoutAssociatedFields[RepositoriesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeResolvableVariable[IndexName]]
    .toSyncDecoder
    .emapE { indices =>
      if(indices.contains(AlreadyResolved(IndexName.all)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(indices.contains(AlreadyResolved(IndexName.wildcard)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => new RepositoriesRule(BaseSpecializedIndicesRule.Settings(indices)))
    .decoder
)

private object IndicesDecodersHelper {
  implicit def indexNameValueDecoder(implicit provider: EnvVarsProvider): Decoder[RuntimeResolvableVariable[IndexName]] =
    DecoderHelpers
      .decodeStringLike
      .toSyncDecoder
      .emapE { str =>
        RuntimeResolvableVariableCreator
          .createFrom(str, extracted => Right(IndexName(extracted)))
          .left.map(error => RulesLevelCreationError(Message(error.msg)))
      }
      .decoder
}