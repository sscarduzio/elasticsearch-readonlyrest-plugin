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
package tech.beshu.ror.accesscontrol.fls

import cats.data.NonEmptyList
import cats.kernel.Monoid
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsedField.SpecificField

object FLS {

  sealed trait Strategy
  object Strategy {
    case object LuceneContextHeaderApproach extends Strategy
    sealed trait BasedOnESRequestContext extends Strategy

    object BasedOnESRequestContext {
      case object NothingNotAllowedToModify extends BasedOnESRequestContext
      final case class NotAllowedFieldsToModify(fields: NonEmptyList[SpecificField]) extends BasedOnESRequestContext
    }
  }

  sealed trait FieldsUsage
  object FieldsUsage {

    case object CantExtractFields extends FieldsUsage
    case object NotUsingFields extends FieldsUsage
    final case class UsingFields(usedFields: NonEmptyList[UsedField]) extends FieldsUsage

    sealed trait UsedField {
      def value: String
    }

    final case class ObfuscatedRandomField(value: String) extends AnyVal
    object ObfuscatedRandomField {
      def apply(from: SpecificField) = {
        //TODO
        val randomValue = "ROR123123123123123"
        new ObfuscatedRandomField(randomValue)
      }
    }

    object UsedField {

      final case class SpecificField private(value: String) extends UsedField

      object SpecificField {
        implicit class Ops (val specificField: SpecificField) extends AnyVal {
          def obfuscate: ObfuscatedRandomField = ObfuscatedRandomField(specificField)
        }
      }

      final case class FieldWithWildcard private(value: String) extends UsedField

      def apply(value: String): UsedField = {
        if (hasWildcard(value))
          FieldWithWildcard(value)
        else
          SpecificField(value)
      }

      private def hasWildcard(fieldName: String): Boolean = fieldName.contains("*")
    }

    implicit val monoidInstance: Monoid[FieldsUsage] = Monoid.instance(NotUsingFields, {
      case (CantExtractFields, _) => CantExtractFields
      case (_, CantExtractFields) => CantExtractFields
      case (other, NotUsingFields) => other
      case (NotUsingFields, other) => other
      case (UsingFields(firstFields), UsingFields(secondFields)) => UsingFields(firstFields ::: secondFields)
    })
  }
}