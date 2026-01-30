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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.Decision.{Permitted, Denied}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{JwtAuthRule, JwtAuthenticationRule, JwtAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.{GroupId, GroupIdPattern}
import tech.beshu.ror.accesscontrol.domain.Jwt.ClaimName
import tech.beshu.ror.accesscontrol.domain.{Jwt as _, *}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.misc.JwtUtils.*
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.security.Key
import scala.concurrent.duration.*
import scala.language.postfixOps

class JwtAuthenticationRuleTokenTests extends JwtTokenTests[JwtAuthenticationRule, AuthenticationJwtDef] {
  override protected def ruleName: String = "jwt_authentication"

  override protected def createJwtDef(id: JwtDef.Name,
                                      authorizationTokenDef: AuthorizationTokenDef,
                                      checkMethod: SignatureCheckMethod,
                                      userClaim: ClaimName,
                                      groupsConfig: GroupsConfig): AuthenticationJwtDef =
    AuthenticationJwtDef(id, authorizationTokenDef, checkMethod, userClaim)

  override protected def createRule(jwtDef: AuthenticationJwtDef): JwtAuthenticationRule =
    new JwtAuthenticationRule(JwtAuthenticationRule.Settings(jwtDef), CaseSensitivity.Enabled)

  override protected def expectedLoggedUser(user: NonEmptyString): Option[LoggedUser] =
    Some(LoggedUser.DirectlyLoggedUser(User.Id(user)))

  override protected def expectedCurrentGroup: Option[GroupId] = None
}

class JwtAuthorizationRuleTokenTests extends JwtTokenTests[JwtAuthorizationRule, AuthorizationJwtDef] {
  override protected def ruleName: String = "jwt_authorization"

  override protected def createJwtDef(id: JwtDef.Name,
                                      authorizationTokenDef: AuthorizationTokenDef,
                                      checkMethod: SignatureCheckMethod,
                                      userClaim: ClaimName,
                                      groupsConfig: GroupsConfig): AuthorizationJwtDef =
    AuthorizationJwtDef(id, authorizationTokenDef, checkMethod, groupsConfig)

  override protected def createRule(jwtDef: AuthorizationJwtDef): JwtAuthorizationRule =
    new JwtAuthorizationRule(JwtAuthorizationRule.Settings(jwtDef, GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdPattern.fromNes(nes("*")))))))

  override protected def expectedLoggedUser(user: NonEmptyString): Option[LoggedUser] = None

  override protected def expectedCurrentGroup: Option[GroupId] = Some(GroupId(nes("group1")))
}

class JwtAuthRuleTokenTests extends JwtTokenTests[JwtAuthRule, AuthJwtDef] {
  override protected def ruleName: String = "jwt_auth"

  override protected def createJwtDef(id: JwtDef.Name,
                                      authorizationTokenDef: AuthorizationTokenDef,
                                      checkMethod: SignatureCheckMethod,
                                      userClaim: ClaimName,
                                      groupsConfig: GroupsConfig): AuthJwtDef =
    AuthJwtDef(id, authorizationTokenDef, checkMethod, userClaim, groupsConfig)

  override protected def createRule(jwtDef: AuthJwtDef): JwtAuthRule = {
    val authentication = new JwtAuthenticationRule(JwtAuthenticationRule.Settings(jwtDef), CaseSensitivity.Enabled)
    val authorization = new JwtAuthorizationRule(JwtAuthorizationRule.Settings(jwtDef, GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdPattern.fromNes(nes("*")))))))
    new JwtAuthRule(authentication, authorization)
  }

  override protected def expectedLoggedUser(user: NonEmptyString): Option[LoggedUser] =
    Some(LoggedUser.DirectlyLoggedUser(User.Id(user)))

  override protected def expectedCurrentGroup: Option[GroupId] =
    Some(GroupId(nes("group1")))
}

trait JwtTokenTests[RULE <: Rule, DEF <: JwtDef]
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion with WithDummyRequestIdSupport {

  protected def ruleName: String

  protected def createJwtDef(id: JwtDef.Name,
                             authorizationTokenDef: AuthorizationTokenDef,
                             checkMethod: SignatureCheckMethod,
                             userClaim: ClaimName,
                             groupsConfig: GroupsConfig): DEF

  protected def createRule(jwtDef: DEF): RULE

  protected def expectedLoggedUser(user: NonEmptyString): Option[LoggedUser]

  protected def expectedCurrentGroup: Option[GroupId]

  s"A $ruleName rule using jwt token" should {
    "match" when {
      "token has valid HS256 signature" in {
        val secret: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(secret, claims = List("userId" := "user", "groups" := List("group1", "group2")))
        assertMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(secret.getEncoded),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              loggedUser = expectedLoggedUser("user"),
              currentGroup = expectedCurrentGroup,
            )(blockContext)
        }
      }
      "token has valid RS256 signature" in {
        val (pub, secret) = Random.generateRsaRandomKeys
        val jwt = Jwt(secret, claims = List("userId" := "user", "groups" := List("group1", "group2")))
        assertMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Rsa(pub),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              loggedUser = expectedLoggedUser("user"),
              currentGroup = expectedCurrentGroup,
            )(blockContext)
        }
      }
      "token has no signature and external auth service returns true" in {
        val jwt = Jwt(claims = List("userId" := "user", "groups" := List("group1", "group2")))
        assertMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.NoCheck(authService(jwt.stringify(), authenticated = true)),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              loggedUser = expectedLoggedUser("user"),
              currentGroup = expectedCurrentGroup,
            )(blockContext)
        }
      }
      "token has no signature and external auth service state is cached" in {
        val validJwt = Jwt(claims = List("userId" := "testuser", "groups" := List("group1", "group2")))
        val invalidJwt = Jwt(claims = List("userId" := "invalid_user", "groups" := List("group1", "group2")))
        val authService = cachedAuthService(validJwt.stringify(), invalidJwt.stringify())
        val jwtDef = createJwtDef(
          JwtDef.Name("test"),
          AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
          SignatureCheckMethod.NoCheck(authService),
          domain.Jwt.ClaimName(jsonPathFrom("userId")),
          GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
        )

        def checkValidToken(): Unit = assertMatchRule(
          configuredJwtDef = jwtDef,
          tokenHeader = bearerHeader(validJwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(validJwt.defaultClaims())),
              loggedUser = expectedLoggedUser("testuser"),
              currentGroup = expectedCurrentGroup,
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
      "custom authorization header is used" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          tokenHeader = bearerHeader("x-jwt-custom-header", jwt)
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              loggedUser = expectedLoggedUser("user1"),
              currentGroup = expectedCurrentGroup,
            )(blockContext)
        }
      }
      "custom authorization token prefix is used" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name("x-jwt-custom-header"), "MyPrefix "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          tokenHeader = new Header(
            Header.Name("x-jwt-custom-header"),
            NonEmptyString.unsafeFrom(s"MyPrefix ${jwt.stringify()}")
          )
        ) {
          blockContext =>
            assertBlockContext(
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              loggedUser = expectedLoggedUser("user1"),
              currentGroup = expectedCurrentGroup,
            )(blockContext)
        }
      }
    }
    "not match" when {
      "token has invalid HS256 signature" in {
        val key1: Key = Jwts.SIG.HS256.key().build()
        val key2: Key = Jwts.SIG.HS256.key().build()
        val jwt2 = Jwt(key2, claims = List("groups" := List("group1", "group2")))
        assertNotMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key1.getEncoded),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader(jwt2)
        )
      }
      "token has invalid RS256 signature" in {
        val (pub, _) = Random.generateRsaRandomKeys
        val (_, secret) = Random.generateRsaRandomKeys
        val jwt = Jwt(secret, claims = List.empty)
        assertNotMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Rsa(pub),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "token has no signature but external auth service returns false" in {
        val jwt = Jwt(claims = List("userId" := "user", "groups" := List("group1", "group2")))
        assertNotMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.NoCheck(authService(jwt.stringify(), authenticated = false)),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "token is invalid and cannot be parsed" in {
        val secret: Key = Jwts.SIG.HS256.key().build()
        assertNotMatchRule(
          configuredJwtDef = createJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(secret.getEncoded),
            domain.Jwt.ClaimName(jsonPathFrom("userId")),
            GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader("INVALID_JWT_TOKEN")
        )
      }
    }
  }

  private def assertMatchRule(configuredJwtDef: DEF,
                              tokenHeader: Header,
                              preferredGroupId: Option[GroupId] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, tokenHeader, preferredGroupId, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: DEF,
                                 tokenHeader: Header,
                                 preferredGroupId: Option[GroupId] = None): Unit =
    assertRule(configuredJwtDef, tokenHeader, preferredGroupId, blockContextAssertion = None)

  private def assertRule(configuredJwtDef: DEF,
                         tokenHeader: Header,
                         preferredGroup: Option[GroupId],
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = createRule(configuredJwtDef)

    val requestContext = MockRequestContext.indices.withHeaders(
      preferredGroup.map(_.toCurrentGroupHeader).toSeq :+ tokenHeader
    )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty,
      allAllowedClusters = Set.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Permitted(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Denied())
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
