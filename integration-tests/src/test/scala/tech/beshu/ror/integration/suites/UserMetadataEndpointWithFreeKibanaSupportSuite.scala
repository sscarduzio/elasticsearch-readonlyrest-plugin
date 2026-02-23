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
import tech.beshu.ror.integration.suites.base.EnabledPromptForBasicAuthSettingSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.elasticsearch.RorApiManager

trait UserMetadataEndpointWithFreeKibanaSupportSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike {
  this: EsClusterProvider with EnabledPromptForBasicAuthSettingSuite =>

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