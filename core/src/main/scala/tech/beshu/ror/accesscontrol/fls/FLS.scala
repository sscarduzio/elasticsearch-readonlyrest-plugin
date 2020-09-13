package tech.beshu.ror.accesscontrol.fls

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField

object FLS {

  sealed trait Strategy
  object Strategy {
    case object LuceneLowLevelApproach extends Strategy
    sealed trait BasedOnESRequestContext extends Strategy

    object BasedOnESRequestContext {
      case object NothingNotAllowedToModify extends BasedOnESRequestContext
      final case class NotAllowedFieldsToModify(fields: NonEmptyList[SpecificField]) extends BasedOnESRequestContext

      final case class NotAllowedField(value: String) extends AnyVal
    }
  }

  sealed trait FieldsUsage

  object FieldsUsage {
    case object NotUsingFields extends FieldsUsage
    sealed trait UsingFields extends FieldsUsage

    object UsingFields {
      case object CantExtractFields extends UsingFields
      final case class FieldsExtractable(usedFields: List[UsedField]) extends UsingFields

      object FieldsExtractable {
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

        def one(field: String): FieldsExtractable = FieldsExtractable(List(UsedField(field)))
      }
    }
  }
}