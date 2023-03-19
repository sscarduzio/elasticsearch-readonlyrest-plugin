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

import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

object FieldsRuleLikeDecoderHelperBase {
  final case class ConfiguredField(fieldName: NonEmptyString,
                                   rawValue: String,
                                   isNegated: Boolean)
}

trait FieldsRuleLikeDecoderHelperBase {

  import FieldsRuleLikeDecoderHelperBase._

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

  protected def documentFieldsDecoder[FIELD](configuredFields: UniqueNonEmptyList[ConfiguredField], alwaysAllowedFields: Set[NonEmptyString])
                                           (implicit itemConvertible: Convertible[FIELD])=
    fromConfiguredFieldsDecoder(configuredFields, createDocumentFields[FIELD](alwaysAllowedFields))

  protected def fromConfiguredFieldsDecoder[ITEM](configuredFields: UniqueNonEmptyList[ConfiguredField],
                                                  creator: UniqueNonEmptyList[ConfiguredField] => Either[CoreCreationError, ITEM]) =
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

  private def createDocumentFields[FIELD](alwaysAllowedFields: Set[NonEmptyString])
                                         (fields: UniqueNonEmptyList[ConfiguredField])
                                         (implicit itemConvertible: Convertible[FIELD])= {
    val fieldsFromAlwaysAllowedList = checkForAlwaysAllowedFields(fields, alwaysAllowedFields)

    if (fieldsFromAlwaysAllowedList.nonEmpty) {
      Left(RulesLevelCreationError(Message(s"These fields cannot be filtered using this rule: ${fieldsFromAlwaysAllowedList.mkString(",")}")))
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

  private def checkForAlwaysAllowedFields(fields: UniqueNonEmptyList[ConfiguredField], alwaysAllowedFields: Set[NonEmptyString]) = {
    fields
      .map(_.fieldName)
      .intersect(alwaysAllowedFields)
  }

  private def createRuntimeVariable[FIELD](field: ConfiguredField)
                                         (implicit itemConvertible: Convertible[FIELD])= {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom[FIELD](field.fieldName)
      .left.map(error => RulesLevelCreationError(Message(error.show)))
  }

  object AccessModeConverter {
    def create[A](whitelistElement: A, blacklistElement: A): AccessModeConverter[A] = new AccessModeConverter[A] {
      override def whitelist: A = whitelistElement

      override def blacklist: A = blacklistElement
    }
  }

  trait AccessModeConverter[A] {
    def whitelist: A
    def blacklist: A
  }
}
