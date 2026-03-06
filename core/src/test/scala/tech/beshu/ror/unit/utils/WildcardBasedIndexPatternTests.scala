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

import cats.implicits.toShow
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.constants.AUDIT_LOG_DEFAULT_INDEX_TEMPLATE
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.WildcardBasedIndexPattern

class WildcardBasedIndexPatternTests extends AnyWordSpec {

  "KibanaIndexPattern" should {
    "return correct Kibana index name pattern for template" when {
      "default template" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(AUDIT_LOG_DEFAULT_INDEX_TEMPLATE).toOption.get
        kibanaIndexPattern.show should be("readonlyrest_audit-*")
      }
      "monthly pattern that is included in docs" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(nes("'custom-prefix'-yyyy-MM")).toOption.get
        kibanaIndexPattern.show should be("custom-prefix-*")
      }
      "pattern in the middle" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(nes("'custom-prefix'-yyyy-MM'some-suffix")).toOption.get
        kibanaIndexPattern.show should be("custom-prefix-*some-suffix")
      }
      "the name pattern is empty after resolving" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(nes("''")).left.toOption.get
        kibanaIndexPattern.show should be("The index name pattern is empty after pattern resolving")
      }
      "the name pattern contains only whitespace after resolving (1)" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(nes(" ''")).left.toOption.get
        kibanaIndexPattern.show should be("The index name pattern contains only whitespace after pattern resolving")
      }
      "the name pattern contains only whitespace after resolving (2)" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(nes("  ")).left.toOption.get
        kibanaIndexPattern.show should be("The index name pattern contains only whitespace after pattern resolving")
      }
      "the name pattern contains only whitespace after resolving (3)" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(nes("'' ")).left.toOption.get
        kibanaIndexPattern.show should be("The index name pattern contains only whitespace after pattern resolving")
      }
      "incomplete string literal in pattern" in {
        val kibanaIndexPattern = WildcardBasedIndexPattern.fromDateTimePatternString(nes("'")).left.toOption.get
        kibanaIndexPattern.show should be("Incomplete string literal in pattern")
      }
    }
  }
}
