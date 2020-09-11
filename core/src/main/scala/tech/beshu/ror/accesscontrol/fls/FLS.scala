package tech.beshu.ror.accesscontrol.fls

import cats.data.NonEmptyList

object FLS {


  sealed trait Strategy
  object Strategy {
    case object LuceneLowLevelApproach extends Strategy
    sealed trait BasedOnESRequestContext extends Strategy

    object BasedOnESRequestContext {
      case object NoQuerySpecified extends BasedOnESRequestContext
      sealed trait QuerySpecified extends BasedOnESRequestContext

      object QuerySpecified {
        case object NoFieldsInQuery extends QuerySpecified
        final case class QueryWithFields(fields: List[UsedField]) extends QuerySpecified

        final case class UsedField(value: String) extends AnyVal
      }
    }
  }

  sealed trait QueryNotAllowedFieldsDetectionResult
  object QueryNotAllowedFieldsDetectionResult {

    case object DoesNotContainNotAllowedFields extends QueryNotAllowedFieldsDetectionResult
    final case class ContainsNotAllowedFields(fields: NonEmptyList[NotAllowedField]) extends QueryNotAllowedFieldsDetectionResult

    final case class NotAllowedField(fieldName: String) extends AnyVal
  }
}
