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
package tech.beshu.ror.integration.suites

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.DocumentManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Version

trait DocumentApiSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with ESVersionSupportForAnyWordSpecLike {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName: String = "/document_api/readonlyrest.yml"

  override lazy val targetEs: EsContainer = container.nodes.head

  private lazy val dev1documentManager = new DocumentManager(basicAuthClient("dev1", "test"), esVersionUsed)

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = DocumentApiSuite.nodeDataInitializer()
    )
  )

  "ROR" when {
    "_mget API is used" should {
      "allow to access all requested indices" when {
        "user has access to all of them" in {
          val result = dev1documentManager.mGet(
            ujson.read(
              """{
                |  "docs":[
                |    {
                |      "_index":"index1_fst",
                |      "_id":1
                |    },
                |    {
                |      "_index":"index1_snd",
                |      "_id":1
                |    }
                |  ]
                |}""".stripMargin
            )
          )

          result.responseCode should be(200)
          result.docs.size should be(2)
          result.docs(0)("_index").str should be("index1_fst")
          result.docs(0)("found").bool should be(true)
          result.docs(1)("_index").str should be("index1_snd")
          result.docs(1)("found").bool should be(true)
        }
      }
      "allow to access only one index" when {
        "the second asked one is forbidden" in {
          val result = dev1documentManager.mGet(
            ujson.read(
              """{
                |  "docs":[
                |    {
                |      "_index":"index1_fst",
                |      "_id":1
                |    },
                |    {
                |      "_index":"index2_fst",
                |      "_id":1
                |    }
                |  ]
                |}""".stripMargin
            )
          )

          result.responseCode should be(200)
          result.docs.size should be(2)
          result.docs(0)("_index").str should be("index1_fst")
          result.docs(0)("found").bool should be(true)
          result.docs(1)("_index").str should startWith("index2_fst")
          result.docs(1)("error")("type").str should be("index_not_found_exception")
        }
      }
      "don't pass through the request if no indices are matched" in {
        val result = dev1documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"index2_fst",
              |      "_id":1
              |    },
              |    {
              |      "_index":"index3_fst",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode should be(401)
      }
    }
    "_bulk API is used" should {
      "allow to create all requests indices" when {
        "user has access to all of them" in {
          val result = dev1documentManager.bulkUnsafe(
            bulkCreateIndexDocEntry("index1_2020-01-01", 1) ++
              bulkCreateIndexDocEntry("index1_2020-01-02", 1): _*
          )

          result.responseCode should be(200)
          val items = result.responseJson("items").arr.toVector
          items(0)("create")("status").num should be(201)
          items(0)("create")("_index").str should be("index1_2020-01-01")
          items(1)("create")("status").num should be(201)
          items(1)("create")("_index").str should be("index1_2020-01-02")
        }
      }
      "not allow to create indices" when {
        "even one index is forbidden" in {
          val result = dev1documentManager.bulkUnsafe(
            bulkCreateIndexDocEntry("index1_2020-01-01", 1) ++
              bulkCreateIndexDocEntry("index2_2020-01-01", 1): _*
          )

          result.responseCode should be(401)
        }
      }
    }
  }

  private def bulkCreateIndexDocEntry(index: String, docId: Int) = {
    if (Version.greaterOrEqualThan(esVersionUsed, 7, 0, 0)) {
      Seq(
        s"""{ "create" : { "_index" : "$index", "_id" : "$docId" } }""",
        """{ "message" : "hello" }"""
      )
    } else {
      Seq(
        s"""{ "create" : { "_index" : "$index", "_type" : "doc", "_id" : "$docId" } }""",
        """{ "message" : "hello" }"""
      )
    }
  }
}

object DocumentApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    documentManager.createFirstDoc("index1_fst", ujson.read("""{"hello":"world"}"""))
    documentManager.createFirstDoc("index1_snd", ujson.read("""{"hello":"world"}"""))
    documentManager.createFirstDoc("index2_fst", ujson.read("""{"hello":"world"}"""))
    documentManager.createFirstDoc("index2_snd", ujson.read("""{"hello":"world"}"""))
  }
}