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
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.{Allow, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

class IndicesYamlLoadedAccessControlTests  extends WordSpec with BaseYamlLoadedAccessControlTest with Inside {
  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Forbidden for .readonlyrest index"
      |    type: "allow"
      |    indices:
      |      patterns: [".readonlyrest"]
      |      must_involve_indices: true #( <true|false|any> normal behaviour without option = any)
      |
    """.stripMargin

  "An ACL" when {
    "indices rule is defined with must_involve_indices: true flag" should {
      "allow to proceed" when {
        "it is an indices request and the requested index is on the configured list" in {
          val request = MockRequestContext.indices.copy(filteredIndices = Set(IndexName(".readonlyrest".nonempty)))
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case Allow(_, _) => }
        }
      }
      "not allow to proceed" when {
        "it is not an indices request" in {
          val request = MockRequestContext.metadata
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case ForbiddenByMismatched(_) => }
        }
      }
    }
  }
}
