package tech.beshu.ror.accesscontrol.fls

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsedField.SpecificField

object FLS {

  sealed trait Strategy
  object Strategy {
    case object LuceneLowLevelApproach extends Strategy
    sealed trait BasedOnESRequestContext extends Strategy

    object BasedOnESRequestContext {
      case object NothingNotAllowedToModify extends BasedOnESRequestContext
      final case class NotAllowedFieldsToModify(fields: NonEmptyList[SpecificField]) extends BasedOnESRequestContext
    }
  }

  sealed trait RequestFieldsUsage
  object RequestFieldsUsage {

    case object CantExtractFields extends RequestFieldsUsage
    case object NotUsingFields extends RequestFieldsUsage
    final case class UsingFields(usedFields: List[UsedField]) extends RequestFieldsUsage

    sealed trait UsedField {
      def value: String
    }

    object UsedField {

      final case class SpecificField private(value: String) extends UsedField
      final case class FieldWithWildcard private(value: String) extends UsedField

      def apply(value: String): UsedField = {
        if (hasWildcard(value)) FieldWithWildcard(value)
        else SpecificField(value)
      }

      private def hasWildcard(fieldName: String): Boolean = fieldName.contains("*")
    }
  }
}