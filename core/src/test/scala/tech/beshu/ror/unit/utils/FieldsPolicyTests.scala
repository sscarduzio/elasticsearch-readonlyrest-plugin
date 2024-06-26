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
package tech.beshu.ror.unit.utils

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class FieldsPolicyTests extends AnyWordSpec {

  "A FieldMatcher" should {
    "work in whitelist mode" in {
      val fields = UniqueNonEmptyList.of(
        DocumentField("it*re*bus1"), DocumentField("item.*Date")
      )
      val fieldsRestrictions = FieldsRestrictions(fields, AccessMode.Whitelist)
      val matcher = new FieldsPolicy(fieldsRestrictions)

      matcher.canKeep("itemresobus2resobus1") should be (true)
      matcher.canKeep("item.endDate") should be (true)
      matcher.canKeep("item") should be (true)
      matcher.canKeep("item.endDate.text") should be (false)
      matcher.canKeep("item.endDate1") should be (false)
    }
    "work in blacklist mode" in {
      val fields = UniqueNonEmptyList.of(
        DocumentField("it*re*bus1"), DocumentField("item.*Date")
      )
      val fieldsRestrictions = FieldsRestrictions(fields, AccessMode.Blacklist)
      val matcher = new FieldsPolicy(fieldsRestrictions)

      matcher.canKeep("itemresobus2resobus1") should be (false)
      matcher.canKeep("item.endDate") should be (false)
      matcher.canKeep("item") should be (true)
      matcher.canKeep("item.endDate.text") should be (false)
      matcher.canKeep("item.endDate1") should be (true)
    }
  }
}
