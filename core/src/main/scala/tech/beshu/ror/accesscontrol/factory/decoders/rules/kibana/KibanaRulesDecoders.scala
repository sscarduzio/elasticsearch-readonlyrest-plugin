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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.kibana

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaHideAppsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.{KibanaAccessRule, KibanaHideAppsRule, KibanaIndexRule, KibanaTemplateIndexRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeResolvableVariableCreator, RuntimeSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.domain.{KibanaAccess, KibanaApp, KibanaIndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.js.JsCompiler
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class KibanaHideAppsRuleDecoder(implicit jsCompiler: JsCompiler)
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaHideAppsRule] {

  override protected def decoder: Decoder[RuleDefinition[KibanaHideAppsRule]] = {
    DecoderHelpers
      .decodeNonEmptyStringLikeOrUniqueNonEmptyList(identity)
      .emap(_.toList.map(KibanaApp.from).traverse(identity))
      .map(apps => new KibanaHideAppsRule(Settings(UniqueNonEmptyList.unsafeFromIterable(apps))))
      .map(RuleDefinition.create(_))
  }
}

class KibanaIndexRuleDecoder(variableCreator: RuntimeResolvableVariableCreator)
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaIndexRule] {

  private implicit val variableCreatorImplicit: RuntimeResolvableVariableCreator = variableCreator

  override protected def decoder: Decoder[RuleDefinition[KibanaIndexRule]] = {
    Decoder[RuntimeSingleResolvableVariable[KibanaIndexName]]
      .map(index => new KibanaIndexRule(KibanaIndexRule.Settings(index)))
      .map(RuleDefinition.create(_))
  }
}

class KibanaTemplateIndexRuleDecoder(variableCreator: RuntimeResolvableVariableCreator)
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaTemplateIndexRule] {

  private implicit val variableCreatorImplicit: RuntimeResolvableVariableCreator = variableCreator

  override protected def decoder: Decoder[RuleDefinition[KibanaTemplateIndexRule]] = {
    Decoder[RuntimeSingleResolvableVariable[KibanaIndexName]]
      .map(index => new KibanaTemplateIndexRule(KibanaTemplateIndexRule.Settings(index)))
      .map(RuleDefinition.create(_))
  }
}

class KibanaAccessRuleDecoder(rorIndexNameConfiguration: RorConfigurationIndex)
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaAccessRule] {

  override protected def decoder: Decoder[RuleDefinition[KibanaAccessRule]] = {
    Decoder[KibanaAccess]
      .map(KibanaAccessRule.Settings(_, rorIndexNameConfiguration))
      .map(settings => new KibanaAccessRule(settings))
      .map(RuleDefinition.create(_))
      .decoder
  }
}
