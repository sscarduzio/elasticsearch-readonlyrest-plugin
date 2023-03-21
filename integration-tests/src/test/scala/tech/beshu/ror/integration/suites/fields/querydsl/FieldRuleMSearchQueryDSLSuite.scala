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
package tech.beshu.ror.integration.suites.fields.querydsl

import tech.beshu.ror.integration.utils.SingletonPluginTestSupport

class FieldRuleMSearchQueryDSLSuite
  extends FieldRuleQueryDSLSuite
    with SingletonPluginTestSupport {

  override protected def assertNoSearchHitsReturnedFor(index: String, query: String) = {
    val result = searchManager.mSearch(s"""{"index":"$index"}""", query.replaceAll("\\n", ""))

    result.responseCode shouldBe 200
    result.responses.size shouldBe 1
    result.searchHitsForResponse(0).isEmpty shouldBe true
  }
}