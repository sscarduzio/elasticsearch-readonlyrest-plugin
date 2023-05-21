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
package tech.beshu.ror.unit.acl.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.Action

class ActionTest extends AnyWordSpec with Matchers {

  "action" when {
    "outdated ror action name is passed" should {
      "be replaced with a valid ror action name" in {
        val expectedRorActionNamesByOutdatedName = Map(
          "cluster:ror/user_metadata/get" -> "cluster:internal_ror/user_metadata/get",
          "cluster:ror/config/manage" -> "cluster:internal_ror/config/manage",
          "cluster:ror/testconfig/manage" -> "cluster:internal_ror/testconfig/manage",
          "cluster:ror/authmock/manage" -> "cluster:internal_ror/authmock/manage",
          "cluster:ror/audit_event/put" -> "cluster:internal_ror/audit_event/put",
          "cluster:ror/config/refreshsettings" -> "cluster:internal_ror/config/refreshsettings"
        )

        val rorActionNamesByOutdatedName =
          expectedRorActionNamesByOutdatedName
            .view
            .map {
              case (oldName, _) => (oldName, Action(oldName).value)
            }
            .toMap

        rorActionNamesByOutdatedName should be(expectedRorActionNamesByOutdatedName)
      }
    }
  }
}
