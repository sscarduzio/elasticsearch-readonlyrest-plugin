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
import tech.beshu.ror.acl.blocks.{Const, Value}
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.rules.{BaseSpecializedIndicesRule, IndicesRule, RepositoriesRule, SnapshotsRule}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.IndicesDecodersHelper._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.domain.IndexName
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.orders._

object IndicesRuleDecoders extends RuleDecoderWithoutAssociatedFields[IndicesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[IndexName]]
    .map(indices => new IndicesRule(IndicesRule.Settings(indices)))
)

object SnapshotsRuleDecoder extends RuleDecoderWithoutAssociatedFields[SnapshotsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[IndexName]]
    .toSyncDecoder
    .emapE { indices =>
      if(indices.contains(Const(IndexName.all)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(indices.contains(Const(IndexName.wildcard)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => new SnapshotsRule(BaseSpecializedIndicesRule.Settings(indices)))
    .decoder
)

object RepositoriesRuleDecoder extends RuleDecoderWithoutAssociatedFields[RepositoriesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[IndexName]]
    .toSyncDecoder
    .emapE { indices =>
      if(indices.contains(Const(IndexName.all)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(indices.contains(Const(IndexName.wildcard)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => new RepositoriesRule(BaseSpecializedIndicesRule.Settings(indices)))
    .decoder
)

private object IndicesDecodersHelper {
  implicit val indexNameValueDecoder: Decoder[Value[IndexName]] =
    DecoderHelpers
      .decodeStringLike
      .toSyncDecoder
      .emapE { e =>
        Value.fromString(e, rv => Right(IndexName(rv.value)))
          .left.map(error => RulesLevelCreationError(Message(error.msg)))
      }
      .decoder
}