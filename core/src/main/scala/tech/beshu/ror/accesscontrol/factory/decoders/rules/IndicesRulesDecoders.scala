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
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.{RepositoriesRule, SnapshotsRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.IndicesDecodersHelper._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RepositoriesDecodersHelper._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.SnapshotDecodersHelper._
import tech.beshu.ror.accesscontrol.matchers.RandomBasedUniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

object IndicesRuleDecoders
  extends RuleBaseDecoderWithoutAssociatedFields[IndicesRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[IndicesRule]] = {
    IndicesRuleDecoders.indicesRuleSimpleDecoder
      .or(IndicesRuleDecoders.indicesRuleExtendedDecoder)
  }

  private val defaultMustInvolveIndicesValue = false

  private lazy val indicesRuleSimpleDecoder: Decoder[RuleWithVariableUsageDefinition[IndicesRule]] =
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]
      .map(indices =>
        RuleWithVariableUsageDefinition.create(
          new IndicesRule(
            settings = IndicesRule.Settings(indices, mustInvolveIndices = defaultMustInvolveIndicesValue),
            identifierGenerator = RandomBasedUniqueIdentifierGenerator
          )
        )
      )

  private lazy val indicesRuleExtendedDecoder: Decoder[RuleWithVariableUsageDefinition[IndicesRule]] = {
    Decoder.instance { c =>
      for {
        indices <- c.downField("patterns").as[NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]]
        mustInvolveIndices <- c.downFields("must_involve_indices").as[Option[Boolean]]
      } yield {
        RuleWithVariableUsageDefinition.create(
          new IndicesRule(
            settings = IndicesRule.Settings(indices, mustInvolveIndices.getOrElse(defaultMustInvolveIndicesValue)),
            identifierGenerator = RandomBasedUniqueIdentifierGenerator
          )
        )
      }
    }
  }

  private implicit lazy val indexNameVariablesDecoder: Decoder[NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]] = {
    DecoderHelpers.decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]
  }
}

object SnapshotsRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[SnapshotsRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[SnapshotsRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]]
      .toSyncDecoder
      .emapE { snapshots =>
        if (SnapshotDecodersHelper.checkIfAlreadyResolvedSnapshotVariableContains(snapshots, SnapshotName.All))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.Name.show}) that matches all the values is redundant - snapshot ${SnapshotName.all.show}")))
        else if (SnapshotDecodersHelper.checkIfAlreadyResolvedSnapshotVariableContains(snapshots, SnapshotName.Wildcard))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.Name.show}) that matches all the values is redundant - snapshot ${SnapshotName.wildcard.show}")))
        else
          Right(snapshots)
      }
      .map(indices => RuleWithVariableUsageDefinition.create(new SnapshotsRule(SnapshotsRule.Settings(indices))))
      .decoder
  }
}

object RepositoriesRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[RepositoriesRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[RepositoriesRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]]
      .toSyncDecoder
      .emapE { repositories =>
        if (checkIfAlreadyResolvedRepositoryVariableContains(repositories, RepositoryName.all))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.Name.show}) that matches all the values is redundant - repository ${RepositoryName.all.show}")))
        else if (checkIfAlreadyResolvedRepositoryVariableContains(repositories, RepositoryName.wildcard))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.Name.show}) that matches all the values is redundant - repository ${RepositoryName.wildcard.show}")))
        else
          Right(repositories)
      }
      .map(repositories => RuleWithVariableUsageDefinition.create(new RepositoriesRule(RepositoriesRule.Settings(repositories))))
      .decoder
  }
}

private object RepositoriesDecodersHelper {
  private implicit val indexNameConvertible: Convertible[RepositoryName] = new Convertible[RepositoryName] {
    override def convert: String => Either[Convertible.ConvertError, RepositoryName] = str =>
      RepositoryName.from(str) match {
        case Some(value) => Right(value)
        case None => Left(Convertible.ConvertError("Repository name cannot be empty"))
      }
  }
  implicit val repositoryValueDecoder: Decoder[RuntimeMultiResolvableVariable[RepositoryName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        RuntimeResolvableVariableCreator
          .createMultiResolvableVariableFrom[RepositoryName](str)
          .left.map(error => RulesLevelCreationError(Message(error.show)))
      }
      .decoder

  private[rules] def checkIfAlreadyResolvedRepositoryVariableContains(repositoriesVars: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]],
                                                                      repository: RepositoryName): Boolean = {
    repositoriesVars
      .find {
        case AlreadyResolved(repositories) => repositories.contains_(repository)
        case ToBeResolved(_) => false
      }
      .isDefined
  }
}

private object SnapshotDecodersHelper {
  private implicit val snapshotNameConvertible: Convertible[SnapshotName] = new Convertible[SnapshotName] {
    override def convert: String => Either[Convertible.ConvertError, SnapshotName] = str =>
      SnapshotName.from(str) match {
        case Some(value) => Right(value)
        case None => Left(Convertible.ConvertError("Snapshot name cannot be empty"))
      }
  }
  implicit val snapshotNameValueDecoder: Decoder[RuntimeMultiResolvableVariable[SnapshotName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        RuntimeResolvableVariableCreator
          .createMultiResolvableVariableFrom[SnapshotName](str)
          .left.map(error => RulesLevelCreationError(Message(error.show)))
      }
      .decoder

  private[rules] def checkIfAlreadyResolvedSnapshotVariableContains(snapshotVars: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]],
                                                                    snapshot: SnapshotName): Boolean = {
    snapshotVars
      .find {
        case AlreadyResolved(snapshots) => snapshots.contains_(snapshot)
        case ToBeResolved(_) => false
      }
      .isDefined
  }
}

private object IndicesDecodersHelper {
  private implicit val indexNameConvertible: Convertible[ClusterIndexName] = new Convertible[ClusterIndexName] {
    override def convert: String => Either[Convertible.ConvertError, ClusterIndexName] = str =>
      ClusterIndexName
        .fromString(str)
        .toRight(Convertible.ConvertError("Index name cannot be empty"))
  }
  implicit val indexNameValueDecoder: Decoder[RuntimeMultiResolvableVariable[ClusterIndexName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        RuntimeResolvableVariableCreator
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