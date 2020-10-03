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
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule.FLSMode
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.FieldsRuleDecoderHelper.flsModeDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

object FieldsRuleDecoder extends RuleDecoderWithAssociatedFields[FieldsRule, FLSMode](
  ruleDecoderCreator = FieldsRuleDecoderHelper.fieldsRuleDecoder,
  associatedFields = NonEmptySet.of("fields_mode"),
  associatedFieldsDecoder =
    Decoder.instance(_.downField("fields_mode").as[Option[FLSMode]])
      .map(_.getOrElse(FLSMode.default))
  )

private object FieldsRuleDecoderHelper {

  private implicit val convertible: Convertible[DocumentField] = AlwaysRightConvertible.from(DocumentField.apply)

  final case class ConfiguredField(fieldName: NonEmptyString,
                                   rawValue: String,
                                   isNegated: Boolean)

  private val configuredFieldsDecoder = DecoderHelpers
    .decodeStringLikeOrUniqueNonEmptyListE(convertToConfiguredField)

  implicit val flsModeDecoder: Decoder[FLSMode] = DecoderHelpers
    .decodeStringLike
    .toSyncDecoder
    .emapE[FLSMode] {
      case "legacy" => Right(FLSMode.Legacy)
      case "hybrid" => Right(FLSMode.Hybrid)
      case "proxy" => Right(FLSMode.Proxy)
      case unknown => Left(AclCreationError.RulesLevelCreationError(Message(s"Unknown fls mode: '$unknown'. Supported: 'legacy', 'hybrid', 'proxy'.")))
    }
    .decoder

  def fieldsRuleDecoder(flsMode: FLSMode) = {
    for {
      configuredFields <- configuredFieldsDecoder
      accessMode <- accessModeDecoder(configuredFields)
      documentFields <- documentFieldsDecoder(configuredFields)
    } yield RuleWithVariableUsageDefinition.create(new FieldsRule(FieldsRule.Settings(documentFields, accessMode, flsMode)))
  }

  private def convertToConfiguredField: String => Either[String, ConfiguredField] = str => {
    if (str.startsWith("~")) {
      NonEmptyString.from(str.substring(1)) match {
        case Right(nes) => Right(ConfiguredField(nes, str, isNegated = true))
        case Left(_) => Left("There was no name passed for blacklist field (~ only is forbidden)")
      }
    } else {
      NonEmptyString.from(str) match {
        case Right(nes) => Right(ConfiguredField(nes, str, isNegated = false))
        case Left(_) => Left("Field cannot be empty string")
      }
    }
  }

  private def accessModeDecoder(configuredFields: UniqueNonEmptyList[ConfiguredField]) =
    fromConfiguredFieldsDecoder(configuredFields, createAccessMode)

  private def documentFieldsDecoder(configuredFields: UniqueNonEmptyList[ConfiguredField]) =
    fromConfiguredFieldsDecoder(configuredFields, createDocumentFields)

  private def fromConfiguredFieldsDecoder[ITEM](configuredFields: UniqueNonEmptyList[ConfiguredField],
                                                creator: UniqueNonEmptyList[ConfiguredField] => Either[AclCreationError, ITEM]) =
    Decoder.const(configuredFields)
      .toSyncDecoder
      .emapE(creator)
      .decoder

  private def createAccessMode(fields: UniqueNonEmptyList[ConfiguredField]) = {
    if (areDifferentAccessModesUsedSimultaneously(fields)) {
      val rawValues = fields.map(field => s"'${field.rawValue}'").mkString(",")
      Left(RulesLevelCreationError(Message(s"fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: $rawValues")))
    } else {
      val usedAccessModes: AccessMode = if (fields.head.isNegated) AccessMode.Blacklist else AccessMode.Whitelist
      Right(usedAccessModes)
    }
  }

  private def createDocumentFields(fields: UniqueNonEmptyList[ConfiguredField]) = {
    if (containsAlwaysAllowedFields(fields)) {
      Left(RulesLevelCreationError(Message(s"The fields rule cannot contain always-allowed fields: ${Constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")))
    } else {
      fields
        .toNonEmptyList
        .traverse(createRuntimeVariable)
        .map(UniqueNonEmptyList.fromNonEmptyList)
    }
  }

  private def areDifferentAccessModesUsedSimultaneously(allFields: UniqueNonEmptyList[ConfiguredField]): Boolean = {
    val (negatedFields, nonNegatedFields) = allFields.partition(_.isNegated)
    negatedFields.nonEmpty && nonNegatedFields.nonEmpty
  }

  private def containsAlwaysAllowedFields(fields: UniqueNonEmptyList[ConfiguredField]): Boolean = {
    fields
      .map(_.fieldName)
      .intersect(Constants.FIELDS_ALWAYS_ALLOW.asScala.map(NonEmptyString.unsafeFrom).toSet)
      .nonEmpty
  }

  private def createRuntimeVariable(field: ConfiguredField) = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom[DocumentField](field.fieldName)
      .left.map(error => RulesLevelCreationError(Message(error.show)))
  }
}
