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
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class ActionTests extends AnyWordSpec with Matchers {

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
    "pattern matching the outdated ror action name is passed" should {
      "be replaced with a pattern matching valid ror action names" in {
        val expectedRorActionPatternsByOutdatedNamePatterns = Map(
          "cluster:r*" -> "cluster:internal_r*",
          "cluster:ro*" -> "cluster:internal_ro*",
          "cluster:ror*" -> "cluster:internal_ror*",
          "cluster:ror/*" -> "cluster:internal_ror/*",
          "cluster:ror/user_metadata/*" -> "cluster:internal_ror/user_metadata/*",
          "cluster:ror/config/*" -> "cluster:internal_ror/config/*",
        )

        val rorActionPatternsByOutdatedNamePatterns =
          expectedRorActionPatternsByOutdatedNamePatterns
            .view
            .map {
              case (oldName, _) => (oldName, Action(oldName).value)
            }
            .toMap

        rorActionPatternsByOutdatedNamePatterns should be(expectedRorActionPatternsByOutdatedNamePatterns)
      }
    }
    "pattern not matching the outdated ror action name is passed" should {
      "be loaded without modification" in {
        val expectedPatterns = Map(
          "indices:admin*" -> "indices:admin*",
          "indices:monitor*" -> "indices:monitor*",
          "indices:data/write*" -> "indices:data/write*",
          "indices:data/read*" -> "indices:data/read*",
          "indices:internal*" -> "indices:internal*",
          "cluster:admin*" -> "cluster:admin*",
          "cluster:monitor*" -> "cluster:monitor*",
          "cluster:internal*" -> "cluster:internal*",
          "internal:*" -> "internal:*"
        )

        val result =
          expectedPatterns
            .view
            .map {
              case (pattern, _) => (pattern, Action(pattern).value)
            }
            .toMap

        result should be(expectedPatterns)
      }
    }
  }
}
