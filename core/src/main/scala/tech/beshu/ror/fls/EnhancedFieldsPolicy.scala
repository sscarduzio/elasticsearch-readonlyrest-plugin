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

class EnhancedFieldsPolicy(fields: NonEmptySet[DocumentField]) {

  private val enhancedFields = fields.toList.map(new FieldsPolicy.EnhancedDocumentField(_))

  def canKeep(field: String): Boolean = {
    Constants.FIELDS_ALWAYS_ALLOW.contains(field) || {
      if (enhancedFields.head.isNegated) {
        !enhancedFields.exists(f => matches(f, field))
      } else {
        enhancedFields.exists(f => matches(f, field))
      }
    }
  }

  private def matches(enhancedField: FieldsPolicy.EnhancedDocumentField,
                      field: String): Boolean = {
    val fieldParts = field.split("\\.").toList
    if (enhancedField.fullPattern.matcher(field).find())
      true
    else if(enhancedField.fieldPartPatterns.length > fieldParts.length)
      false
    else {
      checkPartByPart(enhancedField, fieldParts)
    }
  }

  private def checkPartByPart(enhancedField: FieldsPolicy.EnhancedDocumentField, fieldParts: List[String]) = {
    val allPartsMatch = enhancedField.fieldPartPatterns.zip(fieldParts)
      .forall { case (patternPart, fieldPart) =>
        if (patternPart.pattern() == fieldPart) true
        else wildcardedPatternMatch(patternPart, fieldPart)
      }
    allPartsMatch
  }

  private def wildcardedPatternMatch(pattern: Pattern, value: String): Boolean = {
    pattern.matcher(value).find()
  }

}

