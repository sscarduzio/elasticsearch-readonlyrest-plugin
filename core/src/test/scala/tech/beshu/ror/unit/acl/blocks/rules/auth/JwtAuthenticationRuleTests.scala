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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Jwts
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthenticationRule
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Jwt as _, *}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.misc.JwtUtils.*

import java.security.Key
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

class JwtAuthenticationRuleTests
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion with WithDummyRequestIdSupport {

  "A JwtAuthenticationRule" should {
    "match" when {
      "user claim name is defined and userId is passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1"
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "group IDs claim name is defined and groups are passed in JWT token claim (no preferred group)" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "group IDs claim name is defined and groups are passed in JWT token claim (with preferred group)" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = Some(GroupId("group1"))
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group1"))
            )(blockContext)
        }
      }
      "group IDs claim name is defined as http address and groups are passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "https://{domain}/claims/roles" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "group IDs claim name is defined and no groups field is passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1"
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
            )(blockContext)
        }
      }
      "group IDs claim path is defined and groups are passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "tech" :-> "beshu" :-> "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "group names claim is defined and group names are passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          Claim(NonEmptyList.one(ClaimKey("groups")), List(
            Map("id" -> "group1", "name" -> "Group 1").asJava,
            Map("id" -> "group2", "name" -> "Group 2").asJava
          ).asJava)
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
            )(blockContext)
        }
      }
      "group names claim is defined and group names passed in JWT token claim are malformed" when {
        "group names count differs from the group ID count" in {
          val key: Key = Jwts.SIG.HS256.key().build()
          val jwt = Jwt(key, claims = List(
            "userId" := "user1",
            Claim(NonEmptyList.one(ClaimKey("groups")), List(
              Map("id" -> "group1", "name" -> List("Group 1", "Group A").asJava).asJava,
              Map("id" -> "group2", "name" -> List("Group 2", "Group B").asJava).asJava
            ).asJava)
          ))
          assertMatchRule(
            configuredJwtDef = AuthenticationJwtDef(
              JwtDef.Name("test"),
              AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
              SignatureCheckMethod.Hmac(key.getEncoded),
              userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              )(blockContext)
          }
        }
        "one group does not have a name" in {
          val key: Key = Jwts.SIG.HS256.key().build()
          val jwt = Jwt(key, claims = List(
            "userId" := "user1",
            Claim(NonEmptyList.one(ClaimKey("groups")), List(
              Map("id" -> "group1", "name" -> "Group 1").asJava,
              Map("id" -> "group2").asJava,
              Map("id" -> "group3", "name" -> "Group 3").asJava
            ).asJava)
          ))
          assertMatchRule(
            configuredJwtDef = AuthenticationJwtDef(
              JwtDef.Name("test"),
              AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
              SignatureCheckMethod.Hmac(key.getEncoded),
              userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              )(blockContext)
          }
        }
      }
      "custom authorization header is used" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List("userId" := "user"))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader("x-jwt-custom-header", jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              loggedUser = Some(LoggedUser.DirectlyLoggedUser(User.Id("user"))),
            )(blockContext)
        }
      }
      "custom authorization token prefix is used" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List("userId" := "user"))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "MyPrefix "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = new Header(
            Header.Name("x-jwt-custom-header"),
            NonEmptyString.unsafeFrom(s"MyPrefix ${jwt.stringify()}")
          )
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              loggedUser = Some(LoggedUser.DirectlyLoggedUser(User.Id("user"))),
            )(blockContext)
        }
      }
    }
    "not match" when {
      "user claim name is defined but userId isn't passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "group IDs claim name is defined but groups aren't passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "preferred group is not on the groups list from JWT" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = AuthenticationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = Some(GroupId("group3"))
        )
      }
    }
  }

  private def assertMatchRule(configuredJwtDef: AuthenticationJwtDef,
                              tokenHeader: Header,
                              preferredGroupId: Option[GroupId] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, tokenHeader, preferredGroupId, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: AuthenticationJwtDef,
                                 tokenHeader: Header,
                                 preferredGroupId: Option[GroupId] = None): Unit =
    assertRule(configuredJwtDef, tokenHeader, preferredGroupId, blockContextAssertion = None)

  private def assertRule(configuredJwtDef: AuthenticationJwtDef,
                         tokenHeader: Header,
                         preferredGroup: Option[GroupId],
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new JwtAuthenticationRule(JwtAuthenticationRule.Settings(configuredJwtDef), CaseSensitivity.Enabled)

    val requestContext = MockRequestContext.indices.withHeaders(
      preferredGroup.map(_.toCurrentGroupHeader).toSeq :+ tokenHeader
    )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected())
    }
  }
}
