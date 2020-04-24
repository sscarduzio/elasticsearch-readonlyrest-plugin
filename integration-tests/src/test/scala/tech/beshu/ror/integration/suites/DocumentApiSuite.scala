package tech.beshu.ror.integration.suites

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.DocumentManager
import tech.beshu.ror.utils.httpclient.RestClient

trait DocumentApiSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with ESVersionSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName: String = "/document_api/readonlyrest.yml"

  override lazy val targetEs: EsContainer = container.nodes.head

  private lazy val dev1documentManager = new DocumentManager(basicAuthClient("dev1", "test"))

  override lazy val container: EsClusterContainer = createLocalClusterContainer(
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
    }
    "_bulk API is used" should {
      "allow to create all requests indices" when {
        "user has access to all of them" in {
          val result = dev1documentManager.bulk(
            """{ "create" : { "_index" : "index1_2020-01-01", "_id" : "1" } }""",
            """{ "message" : "hello" }""",
            """{ "create" : { "_index" : "index1_2020-01-02", "_id" : "1" } }""",
            """{ "message" : "hello" }"""
          )

          result.responseCode should be (200)
          val items = result.responseJson("items").arr.toVector
          items(0)("create")("status").num should be (201)
          items(0)("create")("_index").str should be ("index1_2020-01-01")
          items(1)("create")("status").num should be (201)
          items(1)("create")("_index").str should be ("index1_2020-01-02")
        }
      }
      "not allow to create indices" when {
        "even one index is forbidden" in {
          val result = dev1documentManager.bulk(
            """{ "create" : { "_index" : "index1_2020-01-01", "_id" : "1" } }""",
            """{ "message" : "hello" }""",
            """{ "create" : { "_index" : "index2_2020-01-01", "_id" : "1" } }""",
            """{ "message" : "hello" }"""
          )

          result.responseCode should be (401)
        }
      }
    }
  }
}

object DocumentApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)

    documentManager.createDoc("/index1_fst/test/1", ujson.read("""{"hello":"world"}"""))
    documentManager.createDoc("/index1_snd/test/1", ujson.read("""{"hello":"world"}"""))
    documentManager.createDoc("/index2_fst/test/1", ujson.read("""{"hello":"world"}"""))
    documentManager.createDoc("/index2_snd/test/1", ujson.read("""{"hello":"world"}"""))
  }
}