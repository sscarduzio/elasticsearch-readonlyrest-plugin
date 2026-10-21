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

import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.constants

class FieldsPolicy(fieldsRestrictions: FieldsRestrictions) {

  private val enhancedFields = fieldsRestrictions.documentFields.toList.map(new FieldsPolicy.EnhancedDocumentField(_))

  def canKeep(field: String): Boolean = {
    constants.FIELDS_ALWAYS_ALLOW.contains(field) || {
      fieldsRestrictions.mode match {
        case AccessMode.Whitelist =>
          enhancedFields.exists(f => whitelistMatch(f, field))
        case AccessMode.Blacklist =>
          !enhancedFields.exists(f => blacklistMatch(f, field))
      }
    }
  }

  private def whitelistMatch(enhancedField: FieldsPolicy.EnhancedDocumentField, field: String): Boolean = {
    val fieldParts = field.split("\\.").toList
    fieldParts.length <= enhancedField.fieldPartPatterns.length &&
    fieldParts.zip(enhancedField.fieldPartPatterns).forall { case (fieldPart, pattern) => pattern.matches(fieldPart) }
  }

  private def blacklistMatch(enhancedField: FieldsPolicy.EnhancedDocumentField, field: String): Boolean = {
    val fieldParts = field.split("\\.").toList
    enhancedField.fieldPartPatterns.length <= fieldParts.length &&
    enhancedField.fieldPartPatterns.zip(fieldParts).forall { case (pattern, fieldPart) => pattern.matches(fieldPart) }
  }

}

object FieldsPolicy {

  private class EnhancedDocumentField(field: DocumentField) {

    val fieldPartPatterns: List[FieldPartPattern] =
      field.value.value
        .split("\\.")
        .toList
        .map(new FieldPartPattern(_))

  }

  // One dot-separated part of a configured field name. `*` matches any characters, and every other character
  // matches only itself. The field name can come from the request, so matching does not backtrack.
  private final class FieldPartPattern(part: String) {
    private val literals = part.split("\\*", -1).toList

    def matches(fieldPart: String): Boolean = literals match {
      case exact :: Nil =>
        fieldPart == exact
      case prefix :: rest =>
        val suffix = rest.last
        val end = fieldPart.length - suffix.length
        prefix.length <= end && fieldPart.startsWith(prefix) && fieldPart.endsWith(suffix) &&
        containsInOrder(fieldPart, rest.init, from = prefix.length, end)
      case Nil =>
        false
    }

    private def containsInOrder(value: String, infixes: List[String], from: Int, end: Int): Boolean = infixes match {
      case Nil             => true
      case infix :: others =>
        val index = value.indexOf(infix, from)
        index >= 0 && index + infix.length <= end && containsInOrder(value, others, index + infix.length, end)
    }

  }

}
