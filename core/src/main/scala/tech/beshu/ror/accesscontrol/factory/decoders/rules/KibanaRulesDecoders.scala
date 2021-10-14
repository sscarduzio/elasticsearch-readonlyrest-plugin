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

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.{KibanaAccessRule, KibanaHideAppsRule, KibanaIndexRule, KibanaTemplateIndexRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.createSingleResolvableVariableFrom
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, KibanaAccess, KibanaApp, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

object KibanaHideAppsRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaHideAppsRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[KibanaHideAppsRule]] = {
    DecoderHelpers
      .decodeNonEmptyStringLikeOrNonEmptySet(KibanaApp.apply)
      .map(apps => RuleWithVariableUsageDefinition.create(new KibanaHideAppsRule(Settings(apps))))
  }
}

object KibanaIndexRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaIndexRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[KibanaIndexRule]] = {
    KibanaRulesDecoderHelper
      .kibanaIndexDecoder
      .map { index =>
        RuleWithVariableUsageDefinition.create(new KibanaIndexRule(KibanaIndexRule.Settings(index)))
      }
  }
}

object KibanaTemplateIndexRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaTemplateIndexRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[KibanaTemplateIndexRule]] = {
    KibanaRulesDecoderHelper
      .kibanaIndexDecoder
      .map { index =>
        RuleWithVariableUsageDefinition.create(new KibanaTemplateIndexRule(KibanaTemplateIndexRule.Settings(index)))
      }
  }
}

class KibanaAccessRuleDecoder(rorIndexNameConfiguration: RorConfigurationIndex)
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaAccessRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[KibanaAccessRule]] =
    DecoderHelpers
      .decodeStringLike
      .map(_.toLowerCase)
      .toSyncDecoder
      .emapE[KibanaAccess] {
      case "ro" => Right(KibanaAccess.RO)
      case "rw" => Right(KibanaAccess.RW)
      case "ro_strict" => Right(KibanaAccess.ROStrict)
      case "admin" => Right(KibanaAccess.Admin)
      case "unrestricted" => Right(KibanaAccess.Unrestricted)
      case unknown => Left(AclCreationError.RulesLevelCreationError(Message(s"Unknown kibana access '$unknown'")))
    }
      .map(KibanaAccessRule.Settings(_, rorIndexNameConfiguration))
      .map(settings => RuleWithVariableUsageDefinition.create(new KibanaAccessRule(settings)))
      .decoder
}

private object KibanaRulesDecoderHelper {
  implicit val indexNameConvertible: Convertible[ClusterIndexName] = new Convertible[ClusterIndexName] {
    override def convert: String => Either[Convertible.ConvertError, ClusterIndexName] = str => {
      ClusterIndexName.Local
        .fromString(str.replace(" ", "_"))
        .toRight(Convertible.ConvertError("Index name cannot be empty"))
    }
  }
  implicit val kibanaIndexDecoder: Decoder[RuntimeSingleResolvableVariable[ClusterIndexName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(createSingleResolvableVariableFrom[ClusterIndexName])
      .toSyncDecoder
      .emapE {
        case Right(index) => Right(index)
        case Left(error) => Left(RulesLevelCreationError(Message(error.show)))
      }
      .decoder
}

