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

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.constants.AUDIT_LOG_DEFAULT_INDEX_TEMPLATE
import tech.beshu.ror.utils.KibanaIndexPattern

class KibanaIndexPatternTests extends AnyWordSpec {

  "A ToKibanaIndexPatterDateTimeFormatter method kibanaIndexPattern" should {
    "return correct Kibana index name pattern for template" when {
      "default template" in {
        val kibanaIndexPattern = KibanaIndexPattern.fromDateTimeIndexPattern(AUDIT_LOG_DEFAULT_INDEX_TEMPLATE)
        kibanaIndexPattern should be("readonlyrest_audit-*")
      }
      "monthly pattern that is included in docs" in {
        val kibanaIndexPattern = KibanaIndexPattern.fromDateTimeIndexPattern("'custom-prefix'-yyyy-MM")
        kibanaIndexPattern should be("custom-prefix-*")
      }
      "pattern in the middle" in {
        val kibanaIndexPattern = KibanaIndexPattern.fromDateTimeIndexPattern("'custom-prefix'-yyyy-MM'some-suffix")
        kibanaIndexPattern should be("custom-prefix-*some-suffix")
      }
    }
  }
}
