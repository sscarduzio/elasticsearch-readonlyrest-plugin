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

import tech.beshu.ror.integration.suites.base.BaseIndexApiSuite
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.elasticsearch.RorApiManager

class IndexApiWithFreeKibanaSupportSuite
  extends BaseIndexApiSuite
    with SingletonPluginTestSupport {

  override implicit val rorSettingsFileName: String = "/index_api/free_readonlyrest.yml"

  override val notFoundIndexStatusReturned: Int = 401
  override val forbiddenStatusReturned: Int = 401

  "ROR API user metadata endpoint" should {
    "return 403 with message about not supported KBN ROR plugin" when {
      "user would have access, but now does not because of free Kibana" in {
        val userMetadataManager = new RorApiManager(
          basicAuthClientWithRorMetadataAttached("dev1", "test", ("x-ror-kbn-license-type", "ent")),
          esVersionUsed,
        )

        val result = userMetadataManager.fetchUserMetadata()

        result should have statusCode 403
        result.responseJson should be(expectedNotSupportedKbnRorPluginJson)
      }
      "user does not have access" in {
        val userMetadataManager = new RorApiManager(
          basicAuthClientWithRorMetadataAttached("dev9", "test", ("x-ror-kbn-license-type", "ent")),
          esVersionUsed,
        )

        val result = userMetadataManager.fetchUserMetadata()

        result should have statusCode 403
        result.responseJson should be(expectedNotSupportedKbnRorPluginJson)
      }
    }
  }

  "ROR API current user metadata endpoint" should {
    "return 403 with message about not supported KBN ROR plugin" when {
      "user would have access, but now does not because of free Kibana" in {
        val userMetadataManager = new RorApiManager(basicAuthClient("dev1", "test"), esVersionUsed)

        val result = userMetadataManager.fetchCurrentUserMetadata()

        result should have statusCode 403
        result.responseJson should be(expectedNotSupportedKbnRorPluginJson)
      }
      "user does not have access" in {
        val userMetadataManager = new RorApiManager(basicAuthClient("dev9", "test"), esVersionUsed)

        val result = userMetadataManager.fetchCurrentUserMetadata()

        result should have statusCode 403
        result.responseJson should be(expectedNotSupportedKbnRorPluginJson)
      }
    }
  }

  override def forbiddenByBlockResponse(reason: String): ujson.Value = {
    ujson.read(
      s"""
         |{
         |  "error":{
         |    "root_cause":[
         |      {
         |        "type":"forbidden_response",
         |        "reason":"$reason",
         |        "due_to":"FORBIDDEN_BY_BLOCK",
         |        "header":{
         |          "WWW-Authenticate":"Basic"
         |        }
         |      }
         |    ],
         |    "type":"forbidden_response",
         |    "reason":"$reason",
         |    "due_to":"FORBIDDEN_BY_BLOCK",
         |    "header":{
         |      "WWW-Authenticate":"Basic"
         |    }
         |  },
         |  "status":$forbiddenStatusReturned
         |}
         |""".stripMargin
    )
  }

  private def expectedNotSupportedKbnRorPluginJson = ujson.read(
    s"""
       |{
       |  "error":{
       |    "root_cause":[
       |      {
       |        "type":"forbidden_response",
       |        "reason":"The ES ROR is configured with 'prompt_for_basic_auth: true' setting. This setting is appropriate only for using Kibana without KBN ROR plugin. See our docs for details https://docs.readonlyrest.com/elasticsearch#prompt_for_basic_auth",
       |        "due_to":"OPERATION_NOT_ALLOWED"
       |      }
       |    ],
       |    "type":"forbidden_response",
       |    "reason":"The ES ROR is configured with 'prompt_for_basic_auth: true' setting. This setting is appropriate only for using Kibana without KBN ROR plugin. See our docs for details https://docs.readonlyrest.com/elasticsearch#prompt_for_basic_auth",
       |    "due_to":"OPERATION_NOT_ALLOWED"
       |  },
       |  "status":403
       |}
       |""".stripMargin
  )

}