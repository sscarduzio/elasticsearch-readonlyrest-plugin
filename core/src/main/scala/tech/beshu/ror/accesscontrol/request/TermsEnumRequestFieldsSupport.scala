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

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{
  NotUsingFields,
  UsedField,
  UsingFields
}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{
  BasedOnBlockContextOnly,
  FlsAtLuceneLevelApproach
}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, RequestFieldsUsage}

// Elasticsearch's '_terms_enum' API is the same shape (a single 'field' string, an optional 'indexFilter' query) on
// every supported ES version. So the fields rule decision for it is centralized here, against the
// TermsEnumRequestAdapter interface, instead of being duplicated in each es{version}x module.
object TermsEnumRequestFieldsSupport {

  def requestFieldsUsageFor(request: TermsEnumRequestAdapter): RequestFieldsUsage = {
    request.requestedField match {
      case Some(value) => UsingFields(NonEmptyList.one(UsedField(value)))
      case None        => NotUsingFields
    }
  }

  // When the strategy resolves to obfuscating the field, the replacement is applied to 'request' directly (via the
  // TermsEnumRequestAdapter interface) and None is returned. When it resolves to the lucene-level approach, applying
  // it requires the ES module's own FLSContextHeaderHandler/ThreadPool, so the restrictions are returned instead.
  def fieldsRestrictionsToApply(
      request: TermsEnumRequestAdapter,
      fieldLevelSecurity: Option[FieldLevelSecurity]
  ): Option[FieldsRestrictions] = {
    fieldLevelSecurity.flatMap { definedFields =>
      definedFields.strategy match {
        case FlsAtLuceneLevelApproach =>
          Some(definedFields.restrictions)
        case BasedOnBlockContextOnly.NotAllowedFieldsUsed(notAllowedFields) =>
          request.modifyRequestedField(notAllowedFields.head.obfuscate.value)
          None
        case BasedOnBlockContextOnly.EverythingAllowed =>
          None
      }
    }
  }

}
