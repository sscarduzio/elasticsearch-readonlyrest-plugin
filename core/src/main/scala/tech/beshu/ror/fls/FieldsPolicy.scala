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

  def canKeep(field: String): Boolean = {
    constants.FIELDS_ALWAYS_ALLOW.contains(field) || {
      fieldsRestrictions.mode match {
        case AccessMode.Whitelist =>
          fieldsRestrictions.documentFields.exists(f => whitelistMatch(f, field))
        case AccessMode.Blacklist =>
          !fieldsRestrictions.documentFields.exists(f => blacklistMatch(f, field))
      }
    }
  }

  private def whitelistMatch(documentField: DocumentField, field: String): Boolean = {
    val fieldParts = field.split("\\.").toList
    fieldParts.length <= documentField.fieldPartPatterns.length &&
    fieldParts.zip(documentField.fieldPartPatterns).forall { case (fieldPart, pattern) => pattern.matches(fieldPart) }
  }

  private def blacklistMatch(documentField: DocumentField, field: String): Boolean = {
    val fieldParts = field.split("\\.").toList
    documentField.fieldPartPatterns.length <= fieldParts.length &&
    documentField.fieldPartPatterns.zip(fieldParts).forall { case (pattern, fieldPart) => pattern.matches(fieldPart) }
  }

}
