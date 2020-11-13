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
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

object FieldsFilteringRuleBase {
  final case class ConfiguredField(fieldName: NonEmptyString,
                                   rawValue: String,
                                   isNegated: Boolean)
}

trait FieldsFilteringRuleBase {

  import FieldsFilteringRuleBase._

  protected val configuredFieldsDecoder = DecoderHelpers
    .decodeStringLikeOrUniqueNonEmptyListE(convertToConfiguredField)

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

  protected def accessModeDecoder[MODE](configuredFields: UniqueNonEmptyList[ConfiguredField])
                                       (implicit accessModeConverter: AccessModeConverter[MODE]): Decoder[MODE] =
    fromConfiguredFieldsDecoder(configuredFields, createAccessMode[MODE])

  protected def documentFieldsDecoder[FIELD](configuredFields: UniqueNonEmptyList[ConfiguredField], checkForAlwaysAllowedFields: Boolean)
                                           (implicit itemConvertible: Convertible[FIELD])=
    fromConfiguredFieldsDecoder(configuredFields, createDocumentFields[FIELD](checkForAlwaysAllowedFields))

  protected def fromConfiguredFieldsDecoder[ITEM](configuredFields: UniqueNonEmptyList[ConfiguredField],
                                                  creator: UniqueNonEmptyList[ConfiguredField] => Either[AclCreationError, ITEM]) =
    Decoder.const(configuredFields)
      .toSyncDecoder
      .emapE(creator)
      .decoder

  protected def createAccessMode[MODE](fields: UniqueNonEmptyList[ConfiguredField])
                                (implicit accessModeConverter: AccessModeConverter[MODE]): Either[RulesLevelCreationError, MODE] = {
    if (areDifferentAccessModesUsedSimultaneously(fields)) {
      val rawValues = fields.map(field => s"'${field.rawValue}'").mkString(",")
      Left(RulesLevelCreationError(Message(s"fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: $rawValues")))
    } else {
      val usedAccessModes: MODE = if (fields.head.isNegated) accessModeConverter.blacklist else accessModeConverter.whitelist
      Right(usedAccessModes)
    }
  }

  private def createDocumentFields[FIELD](checkForAlwaysAllowedFields: Boolean)
                                         (fields: UniqueNonEmptyList[ConfiguredField])
                                         (implicit itemConvertible: Convertible[FIELD])= {
    if (checkForAlwaysAllowedFields && containsAlwaysAllowedFields(fields)) {
      Left(RulesLevelCreationError(Message(s"The fields rule cannot contain always-allowed fields: ${Constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")))
    } else {
      fields
        .toNonEmptyList
        .traverse(createRuntimeVariable[FIELD])
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

  private def createRuntimeVariable[FIELD](field: ConfiguredField)
                                         (implicit itemConvertible: Convertible[FIELD])= {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom[FIELD](field.fieldName)
      .left.map(error => RulesLevelCreationError(Message(error.show)))
  }

  trait AccessModeConverter[A] {
    def whitelist: A
    def blacklist: A
  }
}
