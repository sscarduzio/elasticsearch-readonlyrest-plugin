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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.elasticsearch

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.elasticsearch.IndicesDecodersHelper._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

class IndicesRuleDecoders(variableCreator: RuntimeResolvableVariableCreator,
                          uniqueIdentifierGenerator: UniqueIdentifierGenerator)
  extends RuleBaseDecoderWithoutAssociatedFields[IndicesRule] {

  private implicit val variableCreatorImplicit: RuntimeResolvableVariableCreator = variableCreator

  override protected def decoder: Decoder[RuleDefinition[IndicesRule]] = {
    indicesRuleSimpleDecoder
      .or(indicesRuleExtendedDecoder)
  }

  private val defaultMustInvolveIndicesValue = false

  private lazy val indicesRuleSimpleDecoder: Decoder[RuleDefinition[IndicesRule]] =
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]
      .map(indices =>
        RuleDefinition.create(
          new IndicesRule(
            settings = IndicesRule.Settings(indices, mustInvolveIndices = defaultMustInvolveIndicesValue),
            identifierGenerator = uniqueIdentifierGenerator
          )
        )
      )

  private lazy val indicesRuleExtendedDecoder: Decoder[RuleDefinition[IndicesRule]] = {
    Decoder.instance { c =>
      for {
        indices <- c.downField("patterns").as[NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]]
        mustInvolveIndices <- c.downFields("must_involve_indices").as[Option[Boolean]]
      } yield {
        RuleDefinition.create(
          new IndicesRule(
            settings = IndicesRule.Settings(indices, mustInvolveIndices.getOrElse(defaultMustInvolveIndicesValue)),
            identifierGenerator = uniqueIdentifierGenerator
          )
        )
      }
    }
  }

  private implicit lazy val indexNameVariablesDecoder: Decoder[NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]] = {
    DecoderHelpers.decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]
  }
}

private object IndicesDecodersHelper {
  private implicit val indexNameConvertible: Convertible[ClusterIndexName] = new Convertible[ClusterIndexName] {
    override def convert: String => Either[Convertible.ConvertError, ClusterIndexName] = str =>
      ClusterIndexName
        .fromString(str)
        .toRight(Convertible.ConvertError("Index name cannot be empty"))
  }
  implicit def indexNameValueDecoder(implicit variableCreator: RuntimeResolvableVariableCreator): Decoder[RuntimeMultiResolvableVariable[ClusterIndexName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        variableCreator
          .createMultiResolvableVariableFrom[ClusterIndexName](str)
          .left.map(error => RulesLevelCreationError(Message(error.show)))
      }
      .decoder

  private[rules] def checkIfAlreadyResolvedIndexVariableContains(indicesVars: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                                                 indexName: ClusterIndexName): Boolean = {
    indicesVars
      .find {
        case AlreadyResolved(indices) => indices.contains_(indexName)
        case ToBeResolved(_) => false
      }
      .isDefined
  }
}