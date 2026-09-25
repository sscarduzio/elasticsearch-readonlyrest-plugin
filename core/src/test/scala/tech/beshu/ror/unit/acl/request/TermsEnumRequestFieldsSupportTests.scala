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
package tech.beshu.ror.unit.acl.request

import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.SpecificField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{
  BasedOnBlockContextOnly,
  FlsAtLuceneLevelApproach
}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, Strategy}
import tech.beshu.ror.accesscontrol.request.{TermsEnumFieldAction, TermsEnumRequestFieldsSupport}
import tech.beshu.ror.utils.TestsUtils.unsafeNes
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class TermsEnumRequestFieldsSupportTests extends AnyWordSpec {

  private val restrictions =
    FieldsRestrictions(UniqueNonEmptyList.of(DocumentField("allowedField")), AccessMode.Whitelist)

  "TermsEnumRequestFieldsSupport#fieldActionFor" should {
    "return NoChange" when {
      "no field level security is defined" in {
        TermsEnumRequestFieldsSupport.fieldActionFor(None) should be(TermsEnumFieldAction.NoChange)
      }
      "the strategy is BasedOnBlockContextOnly.EverythingAllowed" in {
        val fls = FieldLevelSecurity(restrictions, Strategy.BasedOnBlockContextOnly.EverythingAllowed)

        TermsEnumRequestFieldsSupport.fieldActionFor(Some(fls)) should be(TermsEnumFieldAction.NoChange)
      }
    }
    "return ApplyLuceneRestrictions with the original restrictions" when {
      "the strategy is FlsAtLuceneLevelApproach" in {
        val fls = FieldLevelSecurity(restrictions, FlsAtLuceneLevelApproach)

        TermsEnumRequestFieldsSupport.fieldActionFor(Some(fls)) should be(
          TermsEnumFieldAction.ApplyLuceneRestrictions(restrictions)
        )
      }
    }
    "return ObfuscateFieldWith a value derived from the first not-allowed field" when {
      "the strategy is BasedOnBlockContextOnly.NotAllowedFieldsUsed" in {
        val notAllowedField = SpecificField.fromString("notAllowedField")
        val fls = FieldLevelSecurity(
          restrictions,
          BasedOnBlockContextOnly.NotAllowedFieldsUsed(NonEmptyList.one(notAllowedField))
        )

        TermsEnumRequestFieldsSupport.fieldActionFor(Some(fls)) match {
          case TermsEnumFieldAction.ObfuscateFieldWith(value) =>
            value should startWith("notAllowedField_ROR_")
          case other =>
            fail(s"Expected ObfuscateFieldWith, got $other")
        }
      }
    }
  }

}
