package tech.beshu.ror.fls

import java.util.regex.Pattern

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.NegatedDocumentField

class FieldsPolicy(fields: NonEmptySet[DocumentField]) {

  private val enhancedFields = fields.toList.map(new FieldsPolicy.EnhancedDocumentField(_))

  def canKeep(field: String): Boolean = {
    Constants.FIELDS_ALWAYS_ALLOW.contains(field) || {
      if (enhancedFields.head.isNegated) {
        !enhancedFields.exists(f => blacklistMatch(f, field))
      } else {
        enhancedFields.exists(f => whitelistMatch(f, field))
      }
    }
  }

  private def whitelistMatch(enhancedField: FieldsPolicy.EnhancedDocumentField, field: String): Boolean = {
    val fieldParts = field.split("\\.").toList
    if(enhancedField.fieldPartPatterns.length < fieldParts.length) false
    else {
      val foundMismatch = fieldParts.zip(enhancedField.fieldPartPatterns)
        .exists { case (fieldPart, patternPart) =>
          if (fieldPart == patternPart.pattern()) false
          else !wildcardedPatternMatch(patternPart, fieldPart)
        }
      !foundMismatch
    }
  }

  private def blacklistMatch(enhancedField: FieldsPolicy.EnhancedDocumentField, field: String): Boolean = {
    val fieldParts = field.split("\\.").toList
    if(enhancedField.fieldPartPatterns.length  > fieldParts.length) false
    else {
      val foundMismatch = enhancedField.fieldPartPatterns.zip(fieldParts)
        .forall { case (patternPart, fieldPart) =>
          if (patternPart.pattern() == fieldPart) true
          else wildcardedPatternMatch(patternPart, fieldPart)
        }
      foundMismatch
    }
  }

  private def wildcardedPatternMatch(pattern: Pattern, value: String): Boolean = {
    pattern.matcher(value).find()
  }

}

object FieldsPolicy {
  private class EnhancedDocumentField(field: DocumentField) {
    val fieldPartPatterns: List[Pattern] =
      field.value.value
        .split("\\.").toList
        .map { part =>
          Pattern.compile(s"^${part.replace("*", ".*")}$$")
        }

    val isNegated: Boolean = field.isInstanceOf[NegatedDocumentField]

  }
}
