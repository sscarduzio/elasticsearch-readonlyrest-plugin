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

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.suites.fields.sourcefiltering.FieldRuleSourceFilteringSuite.ClientSourceOptions
import tech.beshu.ror.integration.suites.fields.sourcefiltering.FieldRuleSourceFilteringSuite.ClientSourceOptions.{DoNotFetchSource, Exclude, Include}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.elasticsearch.DocumentManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Version

trait FieldRuleSourceFilteringSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  protected type CALL_RESULT <: JsonResponse

  override implicit val rorConfigFileName = "/field_level_security/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldRuleSourceFilteringSuite.nodeDataInitializer())

  protected def fetchDocument(client: RestClient,
                              index: String,
                              clientSourceParams: Option[ClientSourceOptions]): CALL_RESULT

  protected def sourceOfFirstDoc(result: CALL_RESULT): Option[JSON]

  "A fields rule" should {
    "work for simple cases" when {
      "whitelist mode is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user1", "pass"),
          index = "testfiltera",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{"user1":"user1Value"}"""))
      }
      "whitelist mode is used when source should not be fetched" in {
        val result = fetchDocument(
          client = basicAuthClient("user1", "pass"),
          index = "testfiltera",
          clientSourceParams = Some(DoNotFetchSource)
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe None
      }
      "whitelist mode is used with included blacklisted field" in {
        val result = fetchDocument(
          client = basicAuthClient("user1", "pass"),
          index = "testfiltera",
          clientSourceParams = Some(Include("user2"))
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{}"""))
      }
      "whitelist mode is used with excluded whitelisted field" in {
        val result = fetchDocument(
          client = basicAuthClient("user1", "pass"),
          index = "testfiltera",
          clientSourceParams = Some(Exclude("user1"))
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{}"""))
      }
      "whitelist mode with user variable is used " in {
        val result = fetchDocument(
          client = basicAuthClient("user2", "pass"),
          index = "testfiltera",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{"user2":"user2Value"}"""))
      }
      "whitelist mode with user variable is used and called by user with 'negated' value" in {
        val result = fetchDocument(
          client = basicAuthClient("~user", "pass"),
          index = "testfiltera",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{"~user":"~userValue"}"""))
      }
      "whitelist mode with wildcard is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user3", "pass"),
          index = "testfiltera",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{"user3":"user3Value"}"""))
      }
      "whitelist mode is used with wildcard and with included blacklisted field" in {
        val result = fetchDocument(
          client = basicAuthClient("user2", "pass"),
          index = "testfiltera",
          clientSourceParams = Some(Include("us*3"))
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{}"""))
      }

      "blacklist mode is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user4", "pass"),
          index = "testfiltera",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user5": "user5Value",
             | "user6": "user6Value",
             | "counter": 7
             |}""".stripMargin)
        )
      }
      "blacklist mode with user variable is used " in {
        val result = fetchDocument(
          client = basicAuthClient("user5", "pass"),
          index = "testfiltera",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user4": "user4Value",
             | "user6": "user6Value",
             | "counter": 7
             |}""".stripMargin)
        )
      }
      "blacklist mode is used with included blacklisted field" in {
        val result = fetchDocument(
          client = basicAuthClient("user5", "pass"),
          index = "testfiltera",
          clientSourceParams = Some(Include("user5"))
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)

        source shouldBe Some(ujson.read("""{}"""))
      }
      "blacklist mode is used with excluded all fields" in {
        val result = fetchDocument(
          client = basicAuthClient("user5", "pass"),
          index = "testfiltera",
          clientSourceParams = Some(Exclude("*"))
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read("""{}"""))
      }
      "blacklist mode with wildcard is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user6", "pass"),
          index = "testfiltera",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user4": "user4Value",
             | "user5": "user5Value",
             | "counter": 7
             |}""".stripMargin)
        )
      }
    }
    "work for nested fields" when {
      "whitelist mode is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user1", "pass"),
          index = "nestedtest",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read(
          """
            |{
            |  "items":[
            |    {"endDate":"2019-07-31"},
            |    {"endDate":"2019-06-30"},
            |    {"endDate":"2019-09-30"}
            |  ],
            |  "secrets": [
            |    {"key":1, "text": "secret1"},
            |    {"key":2, "text": "secret2"}
            |  ],
            |  "user": {
            |     "name": "value1",
            |     "age": "value2"
            |  }
            |}
            |""".stripMargin
        ))
      }
      "whitelist mode is used with custom including" in {
        val result = fetchDocument(
          client = rorAdminClient,
          index = "nestedtest",
          clientSourceParams = Some(Include("secrets.key"))
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read(
          """
            |{
            |  "secrets": [
            |    {"key":1},
            |    {"key":2}
            |  ]
            |}
            |""".stripMargin
        ))
      }
      "whitelist mode with wildcard is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user2", "pass"),
          index = "nestedtest",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read(
          s"""
             |{
             |  "items":[
             |    {"startDate":"2019-05-22","endDate":"2019-07-31"},
             |    {"startDate":"2019-05-22","endDate":"2019-06-30"},
             |    {"startDate":"2019-05-22","endDate":"2019-09-30"}
             |  ],
             |  "secrets":[
             |    {"key":1,"text":"secret1"},
             |    {"key":2,"text":"secret2"}
             |  ],
             |  "user": {
             |     "name": "value1",
             |     "age": "value2"
             |  }
             |}
           """.stripMargin
        ))
      }
      "blacklist mode is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user3", "pass"),
          index = "nestedtest",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        source shouldBe Some(ujson.read(
          """
            |{
            |  "id":1,
            |  "items":[
            |    {"itemId":1,"text":"text1","startDate":"2019-05-22"},
            |    {"itemId":2,"text":"text2","startDate":"2019-05-22"},
            |    {"itemId":3,"text":"text3","startDate":"2019-05-22"}
            |  ]
            |}""".stripMargin
        ))
      }
      "blacklist mode with wildcards is used" in {
        val result = fetchDocument(
          client = basicAuthClient("user4", "pass"),
          index = "nestedtest",
          clientSourceParams = None
        )

        result.responseCode shouldBe 200

        val source = sourceOfFirstDoc(result)
        //since ES 7.9.0 empty json array is included even when all its items are blacklisted

        val expectedSource =
          if (Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)) {
            """
              |{
              |  "id":1,
              |  "items":[
              |    {"itemId":1,"text":"text1"},
              |    {"itemId":2,"text":"text2"},
              |    {"itemId":3,"text":"text3"}
              |  ],
              |  "secrets": [],
              |  "user":{}
              |}""".stripMargin
          } else {
            """
              |{
              |  "id":1,
              |  "items":[
              |    {"itemId":1,"text":"text1"},
              |    {"itemId":2,"text":"text2"},
              |    {"itemId":3,"text":"text3"}
              |  ],
              |  "user":{}
              |}""".stripMargin
          }

        source shouldBe Some(ujson.read(expectedSource))
      }
    }
  }
}

object FieldRuleSourceFilteringSuite {

  sealed trait ClientSourceOptions

  object ClientSourceOptions {
    case object DoNotFetchSource extends ClientSourceOptions
    final case class Exclude(field: String) extends ClientSourceOptions
    final case class Include(field: String) extends ClientSourceOptions
  }

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    val simpleDoc =
      """{
        | "~user": "~userValue",
        | "user1": "user1Value",
        | "user2": "user2Value",
        | "user3": "user3Value",
        | "user4": "user4Value",
        | "user5": "user5Value",
        | "user6": "user6Value",
        | "counter": 7
        |}""".stripMargin

    val nestedDoc =
      """
        |{
        |  "id":1,
        |  "items": [
        |    {"itemId": 1, "text":"text1", "startDate": "2019-05-22", "endDate": "2019-07-31"},
        |    {"itemId": 2, "text":"text2", "startDate": "2019-05-22", "endDate": "2019-06-30"},
        |    {"itemId": 3, "text":"text3", "startDate": "2019-05-22", "endDate": "2019-09-30"}
        |  ],
        |  "secrets": [
        |    {"key":1, "text": "secret1"},
        |    {"key":2, "text": "secret2"}
        |  ],
        |  "user": {
        |     "name": "value1",
        |     "age": "value2"
        |  }
        |}""".stripMargin

    documentManager.createDoc("testfiltera", 1, ujson.read(simpleDoc)).force()
    documentManager.createDoc("nestedtest", 1, ujson.read(nestedDoc)).force()
  }
}