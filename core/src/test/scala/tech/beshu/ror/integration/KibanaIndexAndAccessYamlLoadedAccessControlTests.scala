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
package tech.beshu.ror.integration

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.{IndexName, KibanaAccess}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.StringOps

class KibanaIndexAndAccessYamlLoadedAccessControlTests extends WordSpec with BaseYamlLoadedAccessControlTest with MockFactory with Inside  {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Template Tenancy"
      |    verbosity: error
      |    kibana_access: admin
      |    kibana_index: ".kibana_template"
      |
      """.stripMargin

  "An ACL" when {
    "kibana index and kibana access rules are used" should {
      "allow to proceed" in {
        val request = MockRequestContext.default

        val result = acl.handleRegularRequest(request).runSyncUnsafe()

        result.history should have size 1
        inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
          block.name should be(Block.Name("Template Tenancy"))
          assertBlockContext(
            kibanaIndex = Some(IndexName(".kibana_template".nonempty)),
            kibanaAccess = Some(KibanaAccess.Admin)
          ) {
            blockContext
          }
        }
      }
    }
  }

}
