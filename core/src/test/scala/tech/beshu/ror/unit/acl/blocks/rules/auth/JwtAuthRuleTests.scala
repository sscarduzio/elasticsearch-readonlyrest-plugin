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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Jwts
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.{GroupsConfig, SignatureCheckMethod}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{JwtAuthRule, JwtAuthenticationRule, JwtAuthorizationRule, JwtPseudoAuthorizationRule}
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
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import java.security.Key
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

class JwtAuthRuleTests
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion with WithDummyRequestIdSupport {

  "A JwtAuthRule" should {
    "match" when {
      "token has valid HS256 signature" in {
        val secret: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(secret, claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(secret.getEncoded),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "token has valid RS256 signature" in {
        val (pub, secret) = Random.generateRsaRandomKeys
        val jwt = Jwt(secret, claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Rsa(pub),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "token has no signature and external auth service returns true" in {
        val jwt = Jwt(claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.NoCheck(authService(jwt.stringify(), authenticated = true)),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "token has no signature and external auth service state is cached" in {
        val validJwt = Jwt(claims = List.empty)
        val invalidJwt = Jwt(claims = List("user" := "testuser"))
        val authService = cachedAuthService(validJwt.stringify(), invalidJwt.stringify())
        val jwtDef = JwtDef(
          JwtDef.Name("test"),
          AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
          SignatureCheckMethod.NoCheck(authService),
          userClaim = None,
          groupsConfig = None
        )

        def checkValidToken(): Unit = assertMatchRule(
          configuredJwtDef = jwtDef,
          tokenHeader = bearerHeader(validJwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(validJwt.defaultClaims()))
            )(blockContext)
        }

        def checkInvalidToken(): Unit = assertNotMatchRule(
          configuredJwtDef = jwtDef,
          tokenHeader = bearerHeader(invalidJwt)
        )

        checkValidToken()
        checkValidToken()
        checkInvalidToken()
        checkValidToken()
      }
      "user claim name is defined and userId is passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1"
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = None,
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
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
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
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
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
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("https://{domain}/claims/roles")), None))
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
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
          ),
          configuredGroups = None,
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
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("tech.beshu.groups")), None))
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
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            ))
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
            configuredJwtDef = JwtDef(
              JwtDef.Name("test"),
              AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
              SignatureCheckMethod.Hmac(key.getEncoded),
              userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
              groupsConfig = Some(GroupsConfig(
                idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
                namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
              ))
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
            configuredJwtDef = JwtDef(
              JwtDef.Name("test"),
              AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
              SignatureCheckMethod.Hmac(key.getEncoded),
              userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
              groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")), Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))))
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
      "rule groups with 'or' logic are defined and intersection between those groups and JWT ones is not empty (1)" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          Claim(NonEmptyList.one(ClaimKey("groups")), List(
            Map("id" -> "group1", "name" -> "Group 1").asJava,
            Map("id" -> "group2", "name" -> "Group 2").asJava
          ).asJava)
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            ))
          ),
          configuredGroups = Some(GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group3"), GroupId("group2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group2")),
              availableGroups = UniqueList.of(group("group2", "Group 2"))
            )(blockContext)
        }
      }
      "rule groups with 'or' logic are defined and intersection between those groups and JWT ones is not empty (2)" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          Claim(NonEmptyList.one(ClaimKey("groups")), List(
            Map("id" -> "group1", "name" -> "Group 1").asJava,
            Map("id" -> "group2", "name" -> "Group 2").asJava
          ).asJava)
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            ))
          ),
          configuredGroups = Some(GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("*2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group2")),
              availableGroups = UniqueList.of(group("group2", "Group 2"))
            )(blockContext)
        }
      }
      "rule groups with 'and' logic are defined and intersection between those groups and JWT ones is not empty (1)" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          Claim(NonEmptyList.one(ClaimKey("groups")), List(
            Map("id" -> "group1", "name" -> "Group 1").asJava,
            Map("id" -> "group2", "name" -> "Group 2").asJava
          ).asJava)
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            ))
          ),
          configuredGroups = Some(GroupsLogic.AllOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group1"), GroupId("group2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group1")),
              availableGroups = UniqueList.of(group("group1", "Group 1"), group("group2", "Group 2"))
            )(blockContext)
        }
      }
      "rule groups with 'and' logic are defined and intersection between those groups and JWT ones is not empty (2)" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          Claim(NonEmptyList.one(ClaimKey("groups")), List(
            Map("id" -> "group1", "name" -> "Group 1").asJava,
            Map("id" -> "group2", "name" -> "Group 2").asJava
          ).asJava)
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            ))
          ),
          configuredGroups = Some(GroupsLogic.AllOf(GroupIds(
            UniqueNonEmptyList.of(GroupIdLike.from("*1"), GroupIdLike.from("*2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group1")),
              availableGroups = UniqueList.of(group("group1", "Group 1"), group("group2", "Group 2"))
            )(blockContext)
        }
      }
      "custom authorization header is used" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = bearerHeader("x-jwt-custom-header", jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "custom authorization token prefix is used" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "MyPrefix "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = new Header(
            Header.Name("x-jwt-custom-header"),
            NonEmptyString.unsafeFrom(s"MyPrefix ${jwt.stringify()}")
          )
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
    }
    "not match" when {
      "token has invalid HS256 signature" in {
        val key1: Key = Jwts.SIG.HS256.key().build()
        val key2: Key = Jwts.SIG.HS256.key().build()
        val jwt2 = Jwt(key2, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key1.getEncoded),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = bearerHeader(jwt2)
        )
      }
      "token has invalid RS256 signature" in {
        val (pub, _) = Random.generateRsaRandomKeys
        val (_, secret) = Random.generateRsaRandomKeys
        val jwt = Jwt(secret, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Rsa(pub),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "token has no signature but external auth service returns false" in {
        val jwt = Jwt(claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.NoCheck(authService(jwt.stringify(), authenticated = false)),
            userClaim = None,
            groupsConfig = None
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "user claim name is defined but userId isn't passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = None
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "group IDs claim name is defined but groups aren't passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "group IDs claim path is wrong" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("tech.beshu.groups.subgroups")), None))
          ),
          configuredGroups = Some(GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group1"))
          ))),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "rule groups with 'or' logic are defined and intersection between those groups and JWT ones is empty" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
          ),
          configuredGroups = Some(GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group3"), GroupId("group4"))
          ))),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "rule groups with 'and' logic are defined and intersection between those groups and JWT ones is empty" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
          ),
          configuredGroups = Some(GroupsLogic.AllOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group2"), GroupId("group3"))
          ))),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "preferred group is not on the groups list from JWT" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = Some(GroupId("group3"))
        )
      }
      "preferred group is not on the permitted groups list" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("userId"))),
            groupsConfig = Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None))
          ),
          configuredGroups = Some(GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group2"))
          ))),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = Some(GroupId("group3"))
        )
      }
    }
  }

  private def assertMatchRule(configuredJwtDef: JwtDef,
                              configuredGroups: Option[GroupsLogic] = None,
                              tokenHeader: Header,
                              preferredGroupId: Option[GroupId] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroupId, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: JwtDef,
                                 configuredGroups: Option[GroupsLogic] = None,
                                 tokenHeader: Header,
                                 preferredGroupId: Option[GroupId] = None): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroupId, blockContextAssertion = None)

  private def assertRule(configuredJwtDef: JwtDef,
                         configuredGroups: Option[GroupsLogic],
                         tokenHeader: Header,
                         preferredGroup: Option[GroupId],
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val authorization: JwtAuthorizationRule | JwtPseudoAuthorizationRule = configuredGroups match {
      case Some(groupsLogic) => new JwtAuthorizationRule(JwtAuthorizationRule.Settings(configuredJwtDef, groupsLogic))
      case None => new JwtPseudoAuthorizationRule(JwtPseudoAuthorizationRule.Settings(configuredJwtDef))
    }
    val rule = new JwtAuthRule(
      new JwtAuthenticationRule(JwtAuthenticationRule.Settings(configuredJwtDef), CaseSensitivity.Enabled),
      authorization,
    )

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

  private def authService(rawToken: String, authenticated: Boolean) = {
    val service = mock[ExternalAuthenticationService]
    (service.authenticate(_: Credentials)(_: RequestId))
      .expects(where { (credentials: Credentials, _) => credentials.secret === PlainTextSecret(NonEmptyString.unsafeFrom(rawToken)) })
      .returning(Task.now(authenticated))
    service
  }

  private def cachedAuthService(authenticatedToken: String, unauthenticatedToken: String) = {
    val service = mock[ExternalAuthenticationService]
    (service.authenticate(_: Credentials)(_: RequestId))
      .expects(where { (credentials: Credentials, _) => credentials.secret === PlainTextSecret(NonEmptyString.unsafeFrom(authenticatedToken)) })
      .returning(Task.now(true))
      .once()
    (service.authenticate(_: Credentials)(_: RequestId))
      .expects(where { (credentials: Credentials, _) => credentials.secret === PlainTextSecret(NonEmptyString.unsafeFrom(unauthenticatedToken)) })
      .returning(Task.now(false))
      .once()
    (() => service.id)
      .expects()
      .returning(Name("external_service"))
    (() => service.serviceTimeout)
      .expects()
      .anyNumberOfTimes()
      .returning(Refined.unsafeApply(10 seconds))
    val ttl = (1 hour).toRefinedPositiveUnsafe
    new CacheableExternalAuthenticationServiceDecorator(service, ttl)
  }
}
