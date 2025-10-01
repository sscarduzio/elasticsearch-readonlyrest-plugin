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

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.ForbiddenCause
import tech.beshu.ror.accesscontrol.AccessControlList.ForbiddenCause.OperationNotAllowed
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult.{Allow, ForbiddenBy, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Header, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.{basicAuthHeader, unsafeNes}
import tech.beshu.ror.accesscontrol.orders.*

import java.util.Base64

class RegularRequestAccessControlTests
  extends AnyWordSpec with BaseYamlLoadedAccessControlTest with Inside {

  protected def settingsYaml: String =
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
      |
      |  - name: "User 2"
      |    type:
      |      policy: forbid
      |      response_message: "you are unauthorized to access this resource"
      |    auth_key: user2:dev
      |
    """.stripMargin

  "An ACL result" should {
    "be allowed" when {
      "regular request is sent in behalf on admin user" in {
        val request = MockRequestContext.indices.withHeaders(basicAuthHeader("admin:container"))
        val result = acl.handleRegularRequest(request).runSyncUnsafe()
        result.history should have size 1
        inside(result.result) { case Allow(blockContext, block) =>
          block.name should be(Block.Name("CONTAINER ADMIN"))
          assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("admin")))) {
            blockContext
          }
        }
      }
      "regular request is sent in behalf of admin user, but the authorization token header is lower case string" in {
        val request = MockRequestContext.indices.withHeaders(
          new Header(
            Name(NonEmptyString.unsafeFrom("authorization")),
            NonEmptyString.unsafeFrom("Basic " + Base64.getEncoder.encodeToString("admin:container".getBytes))
          )
        )
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
    "be forbidden" when {
      "no block was matched" in {
        val request = MockRequestContext.indices.withHeaders(basicAuthHeader("unknown:unknown"))
        val result = acl.handleRegularRequest(request).runSyncUnsafe()
        result.history should have size 3
        inside(result.result) { case ForbiddenByMismatched(causes) =>
          causes should be (NonEmptyList.one[ForbiddenCause](OperationNotAllowed).toNes)
        }
      }
      "the forbid block was matched" in {
        val request = MockRequestContext.indices.withHeaders(basicAuthHeader("user2:dev"))
        val result = acl.handleRegularRequest(request).runSyncUnsafe()
        result.history should have size 3
        inside(result.result) { case ForbiddenBy(blockContext, block) =>
          block.name should be(Block.Name("User 2"))
          block.policy should be(Block.Policy.Forbid(Some("you are unauthorized to access this resource")))
          assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("user2")))) {
            blockContext
          }
        }
      }
    }
  }
}
