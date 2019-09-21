package tech.beshu.ror.fls

import java.util.regex.Pattern

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.NegatedDocumentField

class FieldsPolicy(fields: NonEmptySet[DocumentField]) {

  def canKeep(field: String): Boolean = {
    Constants.FIELDS_ALWAYS_ALLOW.contains(field) || {
      if (fields.head.isInstanceOf[NegatedDocumentField]) {
        !fields.exists(f => blacklistMatch(f.value.value, field))
      } else {
        fields.exists(f => whitelistMatch(f.value.value, field))
      }
    }
  }

  private def whitelistMatch(pattern: String, field: String): Boolean = {
    val patternParts = pattern.split("\\.").toList
    val fieldParts = field.split("\\.").toList
    if(patternParts.length < fieldParts.length) false
    else {
      val foundMismatch = fieldParts.zip(patternParts)
        .exists { case (fieldPart, patternPart) =>
          if (fieldParts == patternParts) false
          else !wildcardedPatternMatch(patternPart, fieldPart)
        }
      !foundMismatch
    }
  }

  private def blacklistMatch(pattern: String, field: String): Boolean = {
    val patternParts = pattern.split("\\.").toList
    val fieldParts = field.split("\\.").toList
    if(patternParts.length > fieldParts.length) false
    else {
      val foundMismatch = patternParts.zip(fieldParts)
        .forall { case (patternPart, fieldPart) =>
          if (patternPart == fieldPart) true
          else wildcardedPatternMatch(patternPart, fieldPart)
        }
      foundMismatch
    }
  }

  private def wildcardedPatternMatch(pattern: String, value: String): Boolean = {
    Pattern.compile(s"^${pattern.replace("*", ".*")}$$").matcher(value).find()
  }

}
