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
import tech.beshu.ror.utils.elasticsearch.IndexManager

trait RorKbnPluginRequestWithFreeKibanaSupportSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike {
  this: EsClusterProvider with EnabledPromptForBasicAuthSettingSuite =>

  "ROR with 'prompt_for_basic_auth: true'" should {
    "ask the client for credentials" when {
      "a forbidden request comes from a client which is not the ROR KBN plugin" in {
        val indexManager = new IndexManager(basicAuthClient("dev9", "test"), esVersionUsed)

        val result = indexManager.getIndex("index9")

        result should have statusCode 401
      }
    }
    "ask for no credentials" when {
      "a forbidden request comes from the ROR KBN plugin" in {
        val indexManager = new IndexManager(
          basicAuthClientWithRorMetadataAttached("dev9", "test", ("x-ror-kbn-license-type", "ent")),
          esVersionUsed,
        )

        val result = indexManager.getIndex("index9")

        result should have statusCode 403
      }
    }
  }

}
