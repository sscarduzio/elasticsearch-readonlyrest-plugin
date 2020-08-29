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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.DocumentManager

trait FieldLevelSecuritySuiteMGetApi
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/field_level_security/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldLevelSecuritySuiteSearchApi.nodeDataInitializer())

  "A fields rule" should {
    "work for simple cases" when {
      "whitelist mode is used" in {
        val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), targetEs.esVersion)

        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{"user1":"user1Value"}"""))
      }
      "whitelist mode is used when source should not be fetched" in {
        val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), targetEs.esVersion)

        val queryParams = Map("_source" -> "false")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )

        result.responseCode shouldBe 200

        result.docs(0).obj.get("_source") shouldBe None
      }
      "whitelist mode is used with included blacklisted field" in {
        val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), targetEs.esVersion)

        val queryParams = Map("_source" -> "user2")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "whitelist mode is used with included allowed field but it's wildcard and it's not matched" in {
        val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), targetEs.esVersion)

        val queryParams = Map("_source" -> "us*1")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{"user1":"user1Value"}"""))
      }
      "whitelist mode is used with excluded whitelisted field" in {
        val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), targetEs.esVersion)

        val queryParams = Map("_source_excludes" -> "user1")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "whitelist mode with user variable is used " in {
        val documentManager = new DocumentManager(basicAuthClient("user2", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{"user2":"user2Value"}"""))
      }
      "whitelist mode with user variable is used and called by user with 'negated' value" in {
        val documentManager = new DocumentManager(basicAuthClient("~user", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{"~user":"~userValue"}"""))
      }
      "whitelist mode with wildcard is used" in {
        val documentManager = new DocumentManager(basicAuthClient("user3", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{"user3":"user3Value"}"""))
      }
      "whitelist mode is used with wildcard and with included blacklisted field" in {
        val documentManager = new DocumentManager(basicAuthClient("user2", "pass"), targetEs.esVersion)

        val queryParams = Map("_source_includes" -> "us*3")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{}"""))
      }

      "blacklist mode is used" in {
        val documentManager = new DocumentManager(basicAuthClient("user4", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read(
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
        val documentManager = new DocumentManager(basicAuthClient("user5", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read(
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
      "blacklist mode is used with included blacklisted field search query" in {
        val documentManager = new DocumentManager(basicAuthClient("user5", "pass"), targetEs.esVersion)

        val queryParams = Map("_source_includes" -> "user5")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "blacklist mode is used with excluded whitelisted field search query" in {
        val documentManager = new DocumentManager(basicAuthClient("user5", "pass"), targetEs.esVersion)

        val queryParams = Map("_source_excludes" -> "*")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "blacklist mode with wildcard is used" in {
        val documentManager = new DocumentManager(basicAuthClient("user6", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"testfiltera",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read(
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
        val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"nestedtest",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read(
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
        val documentManager = new DocumentManager(adminClient, targetEs.esVersion)

        val queryParams = Map("_source_includes" -> "secrets.key")
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"nestedtest",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          ),
          queryParams
        )
        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")
        source should be(ujson.read(
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
        val documentManager = new DocumentManager(basicAuthClient("user2", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"nestedtest",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read(
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
        val documentManager = new DocumentManager(basicAuthClient("user3", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"nestedtest",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read(
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
        val documentManager = new DocumentManager(basicAuthClient("user4", "pass"), targetEs.esVersion)
        val result = documentManager.mGet(
          ujson.read(
            """{
              |  "docs":[
              |    {
              |      "_index":"nestedtest",
              |      "_id":1
              |    }
              |  ]
              |}""".stripMargin
          )
        )

        result.responseCode shouldBe 200

        val source = result.docs(0)("_source")

        source should be(ujson.read(
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
        ))
      }
    }
  }
}