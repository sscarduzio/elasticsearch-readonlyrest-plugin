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
import cats.implicits.*
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.SnapshotsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.SnapshotName
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.elasticsearch.SnapshotDecodersHelper.*
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

class SnapshotsRuleDecoder(variableCreator: RuntimeResolvableVariableCreator)
  extends RuleBaseDecoderWithoutAssociatedFields[SnapshotsRule] {

  private implicit val variableCreatorImplicit: RuntimeResolvableVariableCreator = variableCreator

  override protected def decoder: Decoder[RuleDefinition[SnapshotsRule]] = {
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
      .map(indices => RuleDefinition.create(new SnapshotsRule(SnapshotsRule.Settings(indices))))
      .decoder
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
  implicit def snapshotNameValueDecoder(implicit variableCreator: RuntimeResolvableVariableCreator): Decoder[RuntimeMultiResolvableVariable[SnapshotName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        variableCreator
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
