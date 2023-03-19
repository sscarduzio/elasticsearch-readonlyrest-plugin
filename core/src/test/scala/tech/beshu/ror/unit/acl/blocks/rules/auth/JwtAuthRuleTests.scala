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

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.definitions.{CacheableExternalAuthenticationServiceDecorator, ExternalAuthenticationService, JwtDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthRule.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.DurationOps._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.misc.JwtUtils._
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import java.security.Key
import scala.concurrent.duration._
import scala.language.postfixOps

class JwtAuthRuleTests
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion {

  "A JwtAuthRule" should {
    "match" when {
      "token has valid HS256 signature" in {
        val secret: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(secret, claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(secret.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
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
            groupsClaim = None
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
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
            groupsClaim = None
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
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
          groupsClaim = None
        )
        def checkValidToken = assertMatchRule(
          configuredJwtDef = jwtDef,
          tokenHeader = bearerHeader(validJwt)
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(validJwt.defaultClaims()))
          )(blockContext)
        }
        def checkInvalidToken = assertNotMatchRule(
          configuredJwtDef = jwtDef,
          tokenHeader = bearerHeader(invalidJwt)
        )

        checkValidToken
        checkValidToken
        checkInvalidToken
        checkValidToken
      }
      "user claim name is defined and userId is passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1"
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = None
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
          )(blockContext)
        }
      }
      "groups claim name is defined and groups are passed in JWT token claim (no preferred group)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
          )(blockContext)
        }
      }
      "groups claim name is defined and groups are passed in JWT token claim (with preferred group)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroup = Some(GroupName("group1"))
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims())),
            currentGroup = Some(GroupName("group1"))
          )(blockContext)
        }
      }
      "groups claim name is defined as http address and groups are passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "https://{domain}/claims/roles" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("https://{domain}/claims/roles")))
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
          )(blockContext)
        }
      }
      "groups claim name is defined and no groups field is passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1"
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.NotDefined,
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
          )(blockContext)
        }
      }
      "groups claim path is defined and groups are passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "tech" :-> "beshu" :-> "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("tech.beshu.groups")))
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
          )(blockContext)
        }
      }
      "rule groups with 'or' logic are defined and intersection between those groups and JWT ones is not empty (1)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.Or(PermittedGroups(
            UniqueNonEmptyList.of(GroupName("group3"), GroupName("group2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims())),
            currentGroup = Some(GroupName("group2")),
            availableGroups = UniqueList.of(GroupName("group2"))
          )(blockContext)
        }
      }
      "rule groups with 'or' logic are defined and intersection between those groups and JWT ones is not empty (2)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.Or(PermittedGroups(
            UniqueNonEmptyList.of(GroupName("group3"), GroupLike.from("*2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims())),
            currentGroup = Some(GroupName("group2")),
            availableGroups = UniqueList.of(GroupName("group2"))
          )(blockContext)
        }
      }
      "rule groups with 'and' logic are defined and intersection between those groups and JWT ones is not empty (1)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.And(PermittedGroups(
            UniqueNonEmptyList.of(GroupName("group1"), GroupName("group2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims())),
            currentGroup = Some(GroupName("group1")),
            availableGroups = UniqueList.of(GroupName("group1"), GroupName("group2"))
          )(blockContext)
        }
      }
      "rule groups with 'and' logic are defined and intersection between those groups and JWT ones is not empty (2)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.And(PermittedGroups(
            UniqueNonEmptyList.of(GroupLike.from("*1"), GroupLike.from("*2"))
          ))),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
            jwt = Some(JwtTokenPayload(jwt.defaultClaims())),
            currentGroup = Some(GroupName("group1")),
            availableGroups = UniqueList.of(GroupName("group1"), GroupName("group2"))
          )(blockContext)
        }
      }
      "custom authorization header is used" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = bearerHeader("x-jwt-custom-header", jwt)
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
          )(blockContext)
        }
      }
      "custom authorization token prefix is used" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List.empty)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "MyPrefix "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name("x-jwt-custom-header"),
            NonEmptyString.unsafeFrom(s"MyPrefix ${jwt.stringify()}")
          )
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
          )(blockContext)
        }
      }
    }
    "not match" when {
      "token has invalid HS256 signature" in {
        val key1: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val key2: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt2 = Jwt(key2, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key1.getEncoded),
            userClaim = None,
            groupsClaim = None
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
            groupsClaim = None
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
            groupsClaim = None
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "user claim name is defined but userId isn't passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = None
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "groups claim name is defined but groups aren't passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "groups claim path is wrong" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("tech.beshu.groups.subgroups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.Or(PermittedGroups(
            UniqueNonEmptyList.of(GroupName("group1"))
          ))),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "rule groups with 'or' logic are defined and intersection between those groups and JWT ones is empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.Or(PermittedGroups(
            UniqueNonEmptyList.of(GroupName("group3"), GroupName("group4"))
          ))),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "rule groups with 'and' logic are defined and intersection between those groups and JWT ones is empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.And(PermittedGroups(
            UniqueNonEmptyList.of(GroupName("group2"), GroupName("group3"))
          ))),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "preferred group is not on the groups list from JWT" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroup = Some(GroupName("group3"))
        )
      }
      "preferred group is not on the permitted groups list" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = Groups.Defined(GroupsLogic.Or(PermittedGroups(
            UniqueNonEmptyList.of(GroupName("group2"))
          ))),
          tokenHeader = bearerHeader(jwt),
          preferredGroup = Some(GroupName("group3"))
        )
      }
    }
  }

  private def assertMatchRule(configuredJwtDef: JwtDef,
                              configuredGroups: Groups = Groups.NotDefined,
                              tokenHeader: Header,
                              preferredGroup: Option[GroupName] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroup, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: JwtDef,
                                 configuredGroups: Groups = Groups.NotDefined,
                                 tokenHeader: Header,
                                 preferredGroup: Option[GroupName] = None): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroup, blockContextAssertion = None)

  private def assertRule(configuredJwtDef: JwtDef,
                         configuredGroups: Groups = Groups.NotDefined,
                         tokenHeader: Header,
                         preferredGroup: Option[GroupName],
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new JwtAuthRule(JwtAuthRule.Settings(configuredJwtDef, configuredGroups), UserIdEq.caseSensitive)
    val requestContext = MockRequestContext.indices.copy(
      headers = Set(tokenHeader) ++ preferredGroup.map(_.toCurrentGroupHeader).toSet
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
    (service.authenticate _)
      .expects(where { credentials: Credentials => credentials.secret === PlainTextSecret(NonEmptyString.unsafeFrom(rawToken)) })
      .returning(Task.now(authenticated))
    service
  }

  private def cachedAuthService(authenticatedToken: String, unauthenticatedToken: String) = {
    val service = mock[ExternalAuthenticationService]
    (service.authenticate _)
      .expects(where { credentials: Credentials => credentials.secret === PlainTextSecret(NonEmptyString.unsafeFrom(authenticatedToken)) })
      .returning(Task.now(true))
      .once()
    (service.authenticate _)
      .expects(where { credentials: Credentials => credentials.secret === PlainTextSecret(NonEmptyString.unsafeFrom(unauthenticatedToken)) })
      .returning(Task.now(false))
      .once()
    (service.id _)
      .expects()
      .returning(Name("external_service"))
    (service.serviceTimeout _)
      .expects()
      .anyNumberOfTimes()
      .returning(Refined.unsafeApply(10 seconds))
    val ttl = (1 hour).toRefinedPositiveUnsafe
    new CacheableExternalAuthenticationServiceDecorator(service, ttl)
  }
}
