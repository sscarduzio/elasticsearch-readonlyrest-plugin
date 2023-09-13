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
import tech.beshu.ror.utils.containers.{ComposedElasticsearchNodeDataInitializer, ElasticsearchNodeDataInitializer}
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.SearchManager.SearchResult
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class FieldRuleSearchApiSourceFilteringSuite
  extends FieldRuleSourceFilteringSuite
    with SingletonPluginTestSupport
    with CustomScalaTestMatchers {

  override protected type CALL_RESULT = SearchResult

  override def nodeDataInitializer: Some[ElasticsearchNodeDataInitializer] = Some {
    super.nodeDataInitializer match {
      case Some(initializer) => new ComposedElasticsearchNodeDataInitializer(
        initializer, FieldRuleSearchApiSourceFilteringSuite.nodeDataInitializer()
      )
      case None =>
        FieldRuleSearchApiSourceFilteringSuite.nodeDataInitializer()
    }
  }

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

    result should have statusCode 200

    sourceOfFirstDoc(result) shouldBe Some(ujson.read(
      """|{
         | "user1": "user1Value"
         |}""".stripMargin
    ))

    result.searchHits(0).obj.get("fields") shouldBe None
  }

  "Fields rule should work in case of many docs" when {
    "blacklist mode is used" in {

      val searchManager = new SearchManager(basicAuthClient("user5", "pass"))

      val result = searchManager.search("manydocs", ujson.read("""{"query": {"match_all": {}}}"""))

      result should have statusCode 200
      val distinctDocs = result.searchHits.map(_.obj("_source")).toSet
      distinctDocs should be (Set(ujson.read("""{"user2":"b"}""")))
    }
    "whitelist mode is used" in {
      val searchManager = new SearchManager(basicAuthClient("user6", "pass"))

      val result = searchManager.search("manydocs", ujson.read("""{"query": {"match_all": {}}}"""))

      result should have statusCode 200
      val distinctDocs = result.searchHits.map(_.obj("_source")).toSet
      distinctDocs should be (Set(ujson.read("""{"user1":"a"}""")))
    }
  }
}
object FieldRuleSearchApiSourceFilteringSuite {

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion: String, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    (1 to 50).foreach { idx =>
      documentManager
        .createDoc(
          index = "manydocs",
          id = idx,
          content = ujson.read(
            s"""
               |{
               |  "user1": "a",
               |  "user2": "b"
               |}
               |""".stripMargin)
        )
        .force()
    }
  }
}