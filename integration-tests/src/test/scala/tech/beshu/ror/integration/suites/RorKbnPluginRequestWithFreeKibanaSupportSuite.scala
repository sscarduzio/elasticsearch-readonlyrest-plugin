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
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.elasticsearch.{IndexManager, RorApiManager}

trait RorKbnPluginRequestWithFreeKibanaSupportSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike {
  this: EsClusterProvider with EnabledPromptForBasicAuthSettingSuite =>

  "ROR with 'prompt_for_basic_auth: true'" when {
    "a regular request comes from the ROR KBN plugin" should {
      "ask for no credentials" when {
        "the user does not have access" in {
          val indexManager = new IndexManager(rorKbnPluginClient("dev9", "test"), esVersionUsed)

          val result = indexManager.getIndex("index9")

          result should have statusCode 403
        }
      }
    }
    "a user metadata request comes from the ROR KBN plugin" should {
      "return the user metadata" when {
        "the user has access" in {
          val userMetadataManager = new RorApiManager(rorKbnPluginClient("dev1", "test"), esVersionUsed)

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 200
        }
      }
      "ask for no credentials" when {
        "the user does not have access" in {
          val userMetadataManager = new RorApiManager(rorKbnPluginClient("dev9", "test"), esVersionUsed)

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 403
        }
      }
    }
    "a request comes from a client which is not the ROR KBN plugin" should {
      "ask the client for credentials" when {
        "the user does not have access" in {
          val indexManager = new IndexManager(basicAuthClient("dev9", "test"), esVersionUsed)

          val result = indexManager.getIndex("index9")

          result should have statusCode 401
        }
      }
    }
  }

  private def rorKbnPluginClient(user: String, password: String) =
    basicAuthClientWithRorMetadataAttached(user, password, ("x-ror-kbn-license-type", "ent"))

}
