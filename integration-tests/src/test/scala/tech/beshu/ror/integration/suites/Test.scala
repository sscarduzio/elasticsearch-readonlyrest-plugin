package tech.beshu.ror.integration.suites

import org.scalatest.WordSpec

class Test extends WordSpec {

  "sdfsdf" in {

    val query =
      """
        |{
        |  "query": {
        |    "match": {
        |      "notAllowedField": 999
        |    }
        |  }
        |}
        |""".stripMargin

    println(query.replaceAll("\\n", ""))
    println(query)
  }

}
