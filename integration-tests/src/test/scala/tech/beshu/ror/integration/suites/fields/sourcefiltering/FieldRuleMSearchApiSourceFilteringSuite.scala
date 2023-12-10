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
package tech.beshu.ror.integration.suites.fields.sourcefiltering

import tech.beshu.ror.integration.suites.fields.sourcefiltering.FieldRuleSourceFilteringSuite.ClientSourceOptions.{DoNotFetchSource, Exclude, Include}
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.SearchManager
import tech.beshu.ror.utils.elasticsearch.SearchManager.MSearchResult
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class FieldRuleMSearchApiSourceFilteringSuite
  extends FieldRuleSourceFilteringSuite
    with SingletonPluginTestSupport
    with CustomScalaTestMatchers {

  override protected type CALL_RESULT = MSearchResult

  override protected def fetchDocument(client: RestClient,
                                       index: String,
                                       clientSourceParams: Option[FieldRuleSourceFilteringSuite.ClientSourceOptions]): MSearchResult = {
    val searchManager = new SearchManager(client, esVersionUsed)

    val query = clientSourceParams match {
      case Some(DoNotFetchSource) => """{ "_source": false }"""
      case Some(Include(field)) => s"""{ "_source": { "includes": [ "$field" ] }}"""
      case Some(Exclude(field)) => s"""{ "_source": { "excludes": [ "$field" ] }}"""
      case None => """{}"""
    }

    searchManager.mSearch(s"""{"index":"$index"}""", query)
  }

  override protected def sourceOfFirstDoc(result: MSearchResult): Option[JSON] = {
    val hits = result.searchHitsForResponse(0)
    hits(0).obj.get("_source")
  }

  "docvalue with not-allowed field in search request is used" in {
    val searchManager = new SearchManager(basicAuthClient("user1", "pass"), esVersionUsed)

    val query = """{"docvalue_fields": ["counter"]}"""

    val result = searchManager.mSearch("""{"index":"testfiltera"}""", query)

    result should have statusCode 200
    result.responses.size shouldBe 1
    sourceOfFirstDoc(result) shouldBe Some(ujson.read(
      """|{
         | "user1": "user1Value"
         |}""".stripMargin))

    val hits = result.searchHitsForResponse(0)
    hits(0).obj.get("fields") should be(None)
  }
}