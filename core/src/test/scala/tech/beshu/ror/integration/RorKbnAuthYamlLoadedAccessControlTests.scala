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

import io.jsonwebtoken.security.Keys
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.{ForbiddenCause, RegularRequestResult}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Jwt, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.misc.JwtUtils
import tech.beshu.ror.utils.misc.JwtUtils.ClaimKeyOps
import tech.beshu.ror.utils.uniquelist.UniqueList

class RorKbnAuthYamlLoadedAccessControlTests
  extends AnyWordSpec with BaseYamlLoadedAccessControlTest with Inside {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |  access_control_rules:
      |    - name: Container housekeeping is allowed
      |      type: allow
      |      auth_key: admin:container
      |
      |    - name: Valid JWT token is present
      |      type: allow
      |      ror_kbn_auth:
      |        name: "kbn1"
      |
      |    - name: Valid JWT token is present with another key
      |      type: allow
      |      indices: ["index1"]
      |      ror_kbn_auth:
      |        name: "kbn2"
      |
      |    - name: Valid JWT token is present with a third key + role
      |      type: allow
      |      indices: ["index2"]
      |      groups: ["mapped_viewer_group"]
      |
      |  users:
      |  - username: "*"
      |    groups: ["mapped_viewer_group"]
      |    ror_kbn_auth:
      |      name: "kbn2"
      |      roles: ["viewer_group"]
      |
      |  ror_kbn:
      |
      |    - name: kbn1
      |      signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
      |
      |    - name: kbn2
      |      signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
      |
      |    - name: kbn3
      |      signature_key: "1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890"
      |
    """.stripMargin

  "An ACL" when {
    "is configured using config above" should {
      "allow to proceed" when {
        "JWT token with some arbitrary group is defined" in {
          val jwt = JwtUtils.Jwt(
            secret = Keys.hmacShaKeyFor("123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456".getBytes),
            claims = List("sub" := "test", "user" := "user", "groups" := "arbitrary_group_name")
          )
          val request = MockRequestContext.indices.withHeaders(bearerHeader(jwt))

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Valid JWT token is present"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user"))),
              jwt = Some(Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("arbitrary_group_name"))
            ) {
              blockContext
            }
          }
        }
        "JWT token with some arbitrary group is defined, preferred group is used and matches group in JWT" in {
          val jwt = JwtUtils.Jwt(
            secret = Keys.hmacShaKeyFor("123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456".getBytes),
            claims = List("sub" := "test", "user" := "user", "groups" := "group_in_jwt_token")
          )
          val preferredGroup = group("group_in_jwt_token")
          val request = MockRequestContext.indices.withHeaders(bearerHeader(jwt), preferredGroup.id.toCurrentGroupHeader)

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Valid JWT token is present"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user"))),
              jwt = Some(Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group_in_jwt_token"))
            ) {
              blockContext
            }
          }
        }
        "JWT token with non-empty list of groups is defined, preferred group is used" in {
          val jwt = JwtUtils.Jwt(
            secret = Keys.hmacShaKeyFor("123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456".getBytes),
            claims = List("sub" := "test", "user" := "user", "groups" := List("viewer_group"))
          )
          val preferredGroup = group("mapped_viewer_group")

          val request = MockRequestContext.indices
            .withHeaders(bearerHeader(jwt), preferredGroup.id.toCurrentGroupHeader)
            .copy(
              allAllowedIndices = Set(clusterIndexName("index2")),
              filteredIndices = Set(requestedIndex("index2")),
            )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 4
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Valid JWT token is present with a third key + role"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user"))),
              currentGroup = Some(preferredGroup.id),
              availableGroups = UniqueList.of(preferredGroup),
              indices = Set(requestedIndex("index2")),
              jwt = Some(Jwt.Payload(jwt.defaultClaims()))
            ) {
              blockContext
            }
          }
        }
      }
      "not allow to proceed" when {
        "JWT token with empty list of groups is defined" in {
          val jwt = JwtUtils.Jwt(
            secret = Keys.hmacShaKeyFor("123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456".getBytes),
            claims = List("sub" := "test", "user" := "user", "groups" := "")
          )
          val request = MockRequestContext.indices.withHeaders(bearerHeader(jwt))

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 4
          inside(result.result) { case RegularRequestResult.ForbiddenByMismatched(causes) =>
            causes.toNonEmptyList should have size 1
            causes.head should be(ForbiddenCause.OperationNotAllowed)
          }
        }
        "JWT token with some arbitrary group is defined, preferred group is used and does not match group in JWT" in {
          val jwt = JwtUtils.Jwt(
            secret = Keys.hmacShaKeyFor("123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456".getBytes),
            claims = List("sub" := "test", "user" := "user", "groups" := "arbitrary_group_name")
          )
          val preferredGroup = group("mapped_viewer_group")
          val request = MockRequestContext.indices.withHeaders(bearerHeader(jwt), preferredGroup.id.toCurrentGroupHeader)

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 4
          inside(result.result) { case RegularRequestResult.ForbiddenByMismatched(causes) =>
            causes.toNonEmptyList should have size 1
            causes.head should be(ForbiddenCause.OperationNotAllowed)
          }
        }
      }
    }
  }
}
