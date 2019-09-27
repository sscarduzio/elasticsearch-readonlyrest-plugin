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

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.{BaseSpecializedIndicesRule, IndicesRule, RepositoriesRule, SnapshotsRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.IndicesDecodersHelper._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

class IndicesRuleDecoders extends RuleDecoderWithoutAssociatedFields[IndicesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[IndexName]]
    .map(indices => RuleWithVariableUsageDefinition.create(new IndicesRule(IndicesRule.Settings(indices))))
)

class SnapshotsRuleDecoder extends RuleDecoderWithoutAssociatedFields[SnapshotsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[IndexName]]
    .toSyncDecoder
    .emapE { indices =>
      if(checkIfAlreadyResolvedVariableContains(indices, IndexName.all))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(checkIfAlreadyResolvedVariableContains(indices, IndexName.wildcard))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => RuleWithVariableUsageDefinition.create(new SnapshotsRule(BaseSpecializedIndicesRule.Settings(indices))))
    .decoder
)

class RepositoriesRuleDecoder extends RuleDecoderWithoutAssociatedFields[RepositoriesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[IndexName]]
    .toSyncDecoder
    .emapE { indices =>
      if(checkIfAlreadyResolvedVariableContains(indices, IndexName.all))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(checkIfAlreadyResolvedVariableContains(indices, IndexName.wildcard))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => RuleWithVariableUsageDefinition.create(new RepositoriesRule(BaseSpecializedIndicesRule.Settings(indices))))
    .decoder
)

private object IndicesDecodersHelper {
  private implicit val indexNameConvertible: Convertible[IndexName] = new Convertible[IndexName] {
    override def convert: String => Either[Convertible.ConvertError, IndexName] = str =>
      NonEmptyString
        .from(str)
        .map(IndexName.apply)
        .left.map(_ => Convertible.ConvertError("Index name cannot be empty"))
  }
  implicit val indexNameValueDecoder: Decoder[RuntimeMultiResolvableVariable[IndexName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        RuntimeResolvableVariableCreator
          .createMultiResolvableVariableFrom[IndexName](str)
          .left.map(error => RulesLevelCreationError(Message(error.show)))
      }
      .decoder

  private [rules] def checkIfAlreadyResolvedVariableContains(indicesVars: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                             indexName: IndexName): Boolean = {
    indicesVars
      .find {
        case AlreadyResolved(indices) => indices.contains_(indexName)
        case ToBeResolved(_) => false
      }
      .isDefined
  }
}