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
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.SearchManager
import tech.beshu.ror.utils.elasticsearch.SearchManager.SearchResult
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldRuleSearchApiSourceFilteringSuite
  extends FieldRuleSourceFilteringSuite {
  this: EsClusterProvider =>

  override protected type CALL_RESULT = SearchResult

  override protected def fetchDocument(client: RestClient,
                                       index: String,
                                       clientSourceParams: Option[FieldRuleSourceFilteringSuite.ClientSourceOptions]): SearchResult = {
    val searchManager = new SearchManager(client)

    val query = clientSourceParams match {
      case Some(DoNotFetchSource) => """{ "_source": false }"""
      case Some(Include(field)) => s"""{ "_source": { "includes": [ "$field" ] }}"""
      case Some(Exclude(field)) => s"""{ "_source": { "excludes": [ "$field" ] }}"""
      case None => """{}"""
    }

    searchManager.search(index, ujson.read(query))
  }

  override protected def sourceOfFirstDoc(result: SearchResult): Option[JSON] = {
    result.searchHits(0).obj.get("_source")
  }

  "docvalue with not-allowed field in search request is used" in {
    val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

    val query = ujson.read(
      """
        |{
        |  "docvalue_fields": ["counter"]
        |}
        |""".stripMargin
    )

    val result = searchManager.search("testfiltera", query)

    result.responseCode shouldBe 200

    sourceOfFirstDoc(result) shouldBe Some(ujson.read(
      """|{
         | "user1": "user1Value"
         |}""".stripMargin
    ))

    result.searchHits(0).obj.get("fields") shouldBe None
  }
}