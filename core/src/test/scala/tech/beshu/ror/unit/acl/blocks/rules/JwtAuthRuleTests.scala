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
package tech.beshu.ror.unit.acl.blocks.rules

import java.security.Key

import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.impl.DefaultClaims
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.{Jwts, SignatureAlgorithm}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.definitions.{CacheableExternalAuthenticationServiceDecorator, ExternalAuthenticationService, JwtDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.JwtAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.refined._
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class JwtAuthRuleTests
  extends WordSpec with MockFactory with Inside with BlockContextAssertion {

  "A JwtAuthRule" should {
    "match" when {
      "token has valid HS256 signature" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test").asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.signWith(key).setClaims(claims).compact}")
          )
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "token has valid RS256 signature" in {
        val (pub, secret) = Random.generateRsaRandomKeys
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test").asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Rsa(pub),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setClaims(claims).signWith(secret).compact}")
          )
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "token has no signature and external auth service returns true" in {
        val rawToken = Jwts.builder.setSubject("test").compact
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.NoCheck(authService(rawToken, authenticated = true)),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer $rawToken")
          )
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(new DefaultClaims(Map[String, AnyRef]("sub" -> "test").asJava)))
          )(blockContext)
        }
      }
      "token has no signature and external auth service state is cached" in {
        val validToken = Jwts.builder.setSubject("test").compact
        val invalidToken = Jwts.builder.setClaims(new DefaultClaims(Map[String, AnyRef]("user" -> "testuser").asJava)).compact
        val authService = cachedAuthService(validToken, invalidToken)
        val jwtDef = JwtDef(
          JwtDef.Name("test".nonempty),
          AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
          SignatureCheckMethod.NoCheck(authService),
          userClaim = None,
          groupsClaim = None
        )
        def checkValidToken = assertMatchRule(
          configuredJwtDef = jwtDef,
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer $validToken")
          )
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(new DefaultClaims(Map[String, AnyRef]("sub" -> "test").asJava)))
          )(blockContext)
        }
        def checkInvalidToken = assertNotMatchRule(
          configuredJwtDef = jwtDef,
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer $invalidToken")
          )
        )

        checkValidToken
        checkValidToken
        checkInvalidToken
        checkValidToken
      }
      "user claim name is defined and userId is passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test", "userId" -> "user1").asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setClaims(claims).signWith(key).compact}")
          )
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "groups claim name is defined and groups are passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test", "userId" -> "user1", "groups" -> List("group1", "group2").asJava).asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder.signWith(key).setClaims(claims)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "groups claim name is defined as http address and groups are passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test", "userId" -> "user1", "https://{domain}/claims/roles" -> List("group1", "group2").asJava).asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("https://{domain}/claims/roles")))
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder.signWith(key).setClaims(claims)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "groups claim name is defined and no groups field is passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test", "userId" -> "user1").asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = UniqueList.empty,
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder.signWith(key).setClaims(claims)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "groups claim path is defined and groups are passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef](
          "sub" -> "test",
          "userId" -> "user1",
          "tech" ->  Map("beshu" -> Map("groups" -> List("group1", "group2").asJava).asJava).asJava
        ).asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("tech.beshu.groups")))
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder.signWith(key).setClaims(claims)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "rule groups are defined and intersection between those groups and JWT ones is not empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef](
          "sub" -> "test",
          "userId" -> "user1",
          "groups" -> List("group1", "group2").asJava
        ).asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = UniqueList.of(groupFrom("group3"), groupFrom("group2")),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder.signWith(key).setClaims(claims)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        ) {
          blockContext => assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            jwt = Some(JwtTokenPayload(claims)),
            currentGroup = Some(Group("group2".nonempty)),
            availableGroups = UniqueList.of(Group("group2".nonempty))
          )(blockContext)
        }
      }
      "custom authorization header is used" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test").asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header".nonempty), "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name("x-jwt-custom-header".nonempty),
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setClaims(claims).signWith(key).compact}")
          )
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
      "custom authorization token prefix is used" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test").asJava)
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header".nonempty), "MyPrefix "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name("x-jwt-custom-header".nonempty),
            NonEmptyString.unsafeFrom(s"MyPrefix ${Jwts.builder.setClaims(claims).signWith(key).compact}")
          )
        ) {
          blockContext => assertBlockContext(
            jwt = Some(JwtTokenPayload(claims))
          )(blockContext)
        }
      }
    }
    "not match" when {
      "token has invalid HS256 signature" in {
        val key1: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val key2: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key1.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(key2).compact}")
          )
        )
      }
      "token has invalid RS256 signature" in {
        val (pub, _) = Random.generateRsaRandomKeys
        val (_, secret) = Random.generateRsaRandomKeys
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Rsa(pub),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(secret).compact}")
          )
        )
      }
      "token has no signature but external auth service returns false" in {
        val rawToken = Jwts.builder.setSubject("test").compact
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.NoCheck(authService(rawToken, authenticated = false)),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer $rawToken")
          )
        )
      }
      "user claim name is defined but userId isn't passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = None
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(key).compact}")
          )
        )
      }
      "groups claim name is defined but groups aren't passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(key).compact}")
          )
        )
      }
      "groups claim path is wrong" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("tech.beshu.groups.subgroups")))
          ),
          configuredGroups = UniqueList.of(Group("group1".nonempty)),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder
                .signWith(key)
                .setSubject("test")
                .claim("userId", "user1")
                .claim("groups", List("group1", "group2").asJava)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        )
      }
      "rule groups are defined and intersection between those groups and JWT ones is empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test".nonempty),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = Some(ClaimName(JsonPath.compile("userId"))),
            groupsClaim = Some(ClaimName(JsonPath.compile("groups")))
          ),
          configuredGroups = UniqueList.of(groupFrom("group3"), groupFrom("group4")),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder
                .signWith(key)
                .setSubject("test")
                .claim("userId", "user1")
                .claim("groups", List("group1", "group2").asJava)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        )
      }
    }
  }

  private def assertMatchRule(configuredJwtDef: JwtDef,
                              configuredGroups: UniqueList[Group] = UniqueList.empty,
                              tokenHeader: Header)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: JwtDef,
                                 configuredGroups: UniqueList[Group] = UniqueList.empty,
                                 tokenHeader: Header): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, blockContextAssertion = None)

  private def assertRule(configuredJwtDef: JwtDef,
                         configuredGroups: UniqueList[Group] = UniqueList.empty,
                         tokenHeader: Header,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new JwtAuthRule(JwtAuthRule.Settings(configuredJwtDef, configuredGroups))
    val requestContext = MockRequestContext.metadata.copy(headers = Set(tokenHeader))
    val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty)
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
      .expects(where { credentials: Credentials => credentials.secret === PlainTextSecret(rawToken.nonempty) })
      .returning(Task.now(authenticated))
    service
  }

  private def cachedAuthService(authenticatedToken: String, unauthenticatedToken: String) = {
    val service = mock[ExternalAuthenticationService]
    (service.authenticate _)
      .expects(where { credentials: Credentials => credentials.secret === PlainTextSecret(authenticatedToken.nonempty) })
      .returning(Task.now(true))
      .once()
    (service.authenticate _)
      .expects(where { credentials: Credentials => credentials.secret === PlainTextSecret(unauthenticatedToken.nonempty) })
      .returning(Task.now(false))
      .once()
    (service.id _)
      .expects()
      .returning(Name("external_service"))
    val ttl = refineV[Positive](1 hour).fold(str => throw new Exception(str), identity)
    new CacheableExternalAuthenticationServiceDecorator(service, ttl)
  }
}
