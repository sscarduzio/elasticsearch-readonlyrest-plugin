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

import eu.timepit.refined.auto.*
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Inside, Tag}
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult.Allow
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Header, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.{basicAuthHeader, unsafeNes}

import java.util.Base64

class AuthKeyYamlLoadedAccessControlTests
  extends AnyWordSpec with BaseYamlLoadedAccessControlTest with Inside {

  protected def configYaml: String =
    """
      |other_non_ror_settings:
      |
      |  sth: "sth"
      |
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "CONTAINER ADMIN"
      |    type: allow
      |    auth_key: admin:container
      |
      |  - name: "User 1"
      |    type: allow
      |    auth_key: user1:dev
    """.stripMargin

  "An ACL" when {
    "two blocks with auth_keys are configured" should {
      "allow to proceed" when {
        "request is sent in behalf on admin user" taggedAs Tag("es70x") in {
          val request = MockRequestContext.indices.copy(headers = Set(basicAuthHeader("admin:container")))
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case Allow(blockContext, block) =>
            block.name should be(Block.Name("CONTAINER ADMIN"))
            assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("admin")))) {
              blockContext
            }
          }
        }
        "request is sent in behalf of admin user, but the authorization token header is lower case string" taggedAs Tag("es63x") in {
          val request = MockRequestContext.indices.copy(headers = Set(
            new Header(
              Name(NonEmptyString.unsafeFrom("authorization")),
              NonEmptyString.unsafeFrom("Basic " + Base64.getEncoder.encodeToString("admin:container".getBytes))
            )
          ))

          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case Allow(blockContext, block) =>
            block.name should be(Block.Name("CONTAINER ADMIN"))
            assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("admin")))) {
              blockContext
            }
          }
        }
      }
    }
  }
}
