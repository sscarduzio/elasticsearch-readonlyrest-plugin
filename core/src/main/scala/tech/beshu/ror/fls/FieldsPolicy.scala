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
package tech.beshu.ror.fls

import java.util.regex.Pattern

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.AccessMode

class FieldsPolicy(fields: NonEmptySet[DocumentField]) {

  private val enhancedFields = fields.toList.map(new FieldsPolicy.EnhancedDocumentField(_))

  def canKeep(field: String): Boolean = {
    Constants.FIELDS_ALWAYS_ALLOW.contains(field) || {
      if (enhancedFields.head.isBlacklisted) {
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

    val isBlacklisted: Boolean = field.mode match {
      case AccessMode.Whitelist => false
      case AccessMode.Blacklist => true
    }

  }
}
