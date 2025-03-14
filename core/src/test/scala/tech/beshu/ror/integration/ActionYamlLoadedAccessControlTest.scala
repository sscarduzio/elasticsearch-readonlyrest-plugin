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
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult.{Allow, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.domain.Action
import tech.beshu.ror.mocks.MockRequestContext

class ActionYamlLoadedAccessControlTest extends AnyWordSpec with BaseYamlLoadedAccessControlTest with Inside {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Allowed for user metadata action"
      |    type: "allow"
      |    actions: ["cluster:internal_ror/user_metadata/get"]
      |
      |  - name: "Allowed for test config action"
      |    type: "allow"
      |    actions: ["cluster:internal_ror/testconfig/*"]
      |
      |  - name: "Allowed for auth mock action"
      |    type: "allow"
      |    actions: ["cluster:ror/authmock/manage"] # old format
      |
      |  - name: "Allowed for config action"
      |    type: "allow"
      |    actions: ["cluster:ror/config/*"] # old format
      |
      |  - name: "Allowed for any action"
      |    type: "allow"
      |    actions: ["cluster:r*"] # old format
    """.stripMargin

  "An ACL" when {
    "actions rule is defined" should {
      "allow to proceed" when {
        "it is an metadata request and the action is on the configured list with new name" in {
          val request = MockRequestContext.metadata.copy(action = Action.RorAction.RorUserMetadataAction)
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(_, block) =>
            block.name.value should be("Allowed for user metadata action")
          }
        }
        "it is a test config request and the action name match pattern on the configured list" in {
          val request = MockRequestContext.indices.copy(action = Action.RorAction.RorTestConfigAction)
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(_, block) =>
            block.name.value should be("Allowed for test config action")
          }
        }
        "it is a auth mock request and the action is on the configured list with old name" in {
          val request = MockRequestContext.indices.copy(action = Action.RorAction.RorAuthMockAction)
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(_, block) =>
            block.name.value should be("Allowed for auth mock action")
          }
        }
        "it is a config request and the action name match pattern on the configured list with old name" in {
          val request = MockRequestContext.indices.copy(action = Action.RorAction.RorConfigAction)
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(_, block) =>
            block.name.value should be("Allowed for config action")
          }
        }
        "it is a audit event request and the action match pattern on the configured list with old name" in {
          val request = MockRequestContext.indices.copy(action = Action.RorAction.RorAuditEventAction)
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(_, block) =>
            block.name.value should be("Allowed for any action")
          }
        }
      }
      "not allow to proceed" when {
        "the action is not on the configured list" in {
          val request = MockRequestContext.metadata.copy(action = MockRequestContext.roAction)
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          inside(result.result) { case ForbiddenByMismatched(_) => }
        }
      }
    }
  }

}
