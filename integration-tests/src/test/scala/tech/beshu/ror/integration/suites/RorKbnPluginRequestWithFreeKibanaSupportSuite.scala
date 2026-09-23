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

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.EnabledPromptForBasicAuthSettingSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleResponse
import tech.beshu.ror.utils.elasticsearch.{IndexManager, RorApiManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait RorKbnPluginRequestWithFreeKibanaSupportSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike {
  this: EsClusterProvider with EnabledPromptForBasicAuthSettingSuite =>

  "ROR with 'prompt_for_basic_auth: true'" when {
    "a regular request comes from the ROR KBN plugin" should {
      "ask for no credentials" when {
        "a block forbids the user" in {
          val result = new IndexManager(rorKbnPluginClient("dev9", "test"), esVersionUsed).getIndex("index9")

          result should have statusCode 403
          result.responseJson("error")("due_to").str should be("FORBIDDEN_BY_BLOCK")
          basicAuthPromptOf(result) should be(None)
        }
        // The plugin sends the header as a plain HTTP header. The ror_metadata transport above is the
        // one which the ROR KBN plugin uses for the headers it forwards from a browser.
        "the header comes as a plain HTTP header" in {
          val client = basicAuthClientWithHeaders("dev9", "test", ("x-ror-kbn-license-type", "ent"))

          val result = new IndexManager(client, esVersionUsed).getIndex("index9")

          result should have statusCode 403
          basicAuthPromptOf(result) should be(None)
        }
      }
      "hide an index which the user cannot see" when {
        "the index exists" in {
          val result = new IndexManager(rorKbnPluginClient("dev1", "test"), esVersionUsed).getIndex("index2")

          result should have statusCode 404
          basicAuthPromptOf(result) should be(None)
        }
        "the index does not exist" in {
          val result = new IndexManager(rorKbnPluginClient("dev1", "test"), esVersionUsed).getIndex("index3")

          result should have statusCode 404
          basicAuthPromptOf(result) should be(None)
        }
        "an alias of the index is requested" in {
          val result = new IndexManager(rorKbnPluginClient("dev1", "test"), esVersionUsed).getAlias("index2")

          result should have statusCode 404
          basicAuthPromptOf(result) should be(None)
        }
      }
    }
    "a user metadata request comes from the ROR KBN plugin" should {
      "return the user metadata" when {
        "the user has access" in {
          val result = new RorApiManager(rorKbnPluginClient("dev1", "test"), esVersionUsed).fetchUserMetadata()

          result should have statusCode 200
        }
      }
      "ask for no credentials" when {
        "the user does not have access" in {
          val result = new RorApiManager(rorKbnPluginClient("dev9", "test"), esVersionUsed).fetchUserMetadata()

          result should have statusCode 403
          basicAuthPromptOf(result) should be(None)
        }
      }
    }
    "a request comes from a client which is not the ROR KBN plugin" should {
      "ask the client for credentials" when {
        "a block forbids the user" in {
          val result = new IndexManager(basicAuthClient("dev9", "test"), esVersionUsed).getIndex("index9")

          result should have statusCode 401
          basicAuthPromptOf(result) should be(Some("Basic"))
        }
        "the user cannot see the index" in {
          val result = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed).getIndex("index2")

          result should have statusCode 401
          basicAuthPromptOf(result) should be(Some("Basic"))
        }
      }
    }
  }

  private def rorKbnPluginClient(user: String, password: String): RestClient =
    basicAuthClientWithRorMetadataAttached(user, password, ("x-ror-kbn-license-type", "ent"))

  private def basicAuthPromptOf(response: SimpleResponse): Option[String] =
    response.headers.find(_.name.toLowerCase == "www-authenticate").map(_.value)

}
