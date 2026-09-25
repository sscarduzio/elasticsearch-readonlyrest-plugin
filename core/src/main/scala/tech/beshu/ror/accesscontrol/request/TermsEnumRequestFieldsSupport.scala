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
package tech.beshu.ror.accesscontrol.request

import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{
  BasedOnBlockContextOnly,
  FlsAtLuceneLevelApproach
}

sealed trait TermsEnumFieldAction

object TermsEnumFieldAction {
  case object NoChange extends TermsEnumFieldAction
  final case class ApplyLuceneRestrictions(restrictions: FieldsRestrictions) extends TermsEnumFieldAction
  final case class ObfuscateFieldWith(value: String) extends TermsEnumFieldAction
}

object TermsEnumRequestFieldsSupport {

  def fieldActionFor(fieldLevelSecurity: Option[FieldLevelSecurity]): TermsEnumFieldAction = {
    fieldLevelSecurity match {
      case None =>
        TermsEnumFieldAction.NoChange
      case Some(definedFields) =>
        definedFields.strategy match {
          case FlsAtLuceneLevelApproach =>
            TermsEnumFieldAction.ApplyLuceneRestrictions(definedFields.restrictions)
          case BasedOnBlockContextOnly.NotAllowedFieldsUsed(notAllowedFields) =>
            TermsEnumFieldAction.ObfuscateFieldWith(notAllowedFields.head.obfuscate.value)
          case BasedOnBlockContextOnly.EverythingAllowed =>
            TermsEnumFieldAction.NoChange
        }
    }
  }

}
