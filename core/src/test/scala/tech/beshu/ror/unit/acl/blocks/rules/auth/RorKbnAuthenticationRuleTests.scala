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

import io.jsonwebtoken.Jwts
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthenticationRule
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Jwt as _, *}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.misc.JwtUtils.*
import tech.beshu.ror.utils.misc.Random

import java.security.Key
import scala.concurrent.duration.*
import scala.language.postfixOps

class RorKbnAuthenticationRuleTests
  extends AnyWordSpec with Inside with BlockContextAssertion {

  "A RorKbnAuthenticationRule" should {
    "match" when {
      "token has valid HS256 signature" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
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
      "token has valid RS256 signature" in {
        val (pub, secret) = Random.generateRsaRandomKeys
        val jwt = Jwt(secret, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Rsa(pub)
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
      "groups claim name is defined and no groups field is passed in token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
        ))
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
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
      "preferred group is not on the groups list from JWT" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = Some(GroupId("group5"))
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group5"))
            )(blockContext)
        }
      }
    }
    "not match" when {
      "token has invalid HS256 signature" in {
        val key1: Key = Jwts.SIG.HS256.key().build()
        val key2: Key = Jwts.SIG.HS256.key().build()
        val jwt2 = Jwt(key2, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key1.getEncoded)
          ),
          tokenHeader = bearerHeader(jwt2)
        )
      }
      "token has invalid RS256 signature" in {
        val (pub, _) = Random.generateRsaRandomKeys
        val (_, secret) = Random.generateRsaRandomKeys
        val jwt = Jwt(secret, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Rsa(pub)
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "userId isn't passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
    }
  }

  private def assertMatchRule(configuredRorKbnDef: RorKbnDef,
                              tokenHeader: Header,
                              preferredGroupId: Option[GroupId] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredRorKbnDef, tokenHeader, preferredGroupId, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredRorKbnDef: RorKbnDef,
                                 tokenHeader: Header,
                                 preferredGroupId: Option[GroupId] = None): Unit =
    assertRule(configuredRorKbnDef, tokenHeader, preferredGroupId, blockContextAssertion = None)

  private def assertRule(configuredRorKbnDef: RorKbnDef,
                         tokenHeader: Header,
                         preferredGroupId: Option[GroupId],
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new RorKbnAuthenticationRule(RorKbnAuthenticationRule.Settings(configuredRorKbnDef), CaseSensitivity.Enabled)
    val requestContext = MockRequestContext.indices.withHeaders(
      preferredGroupId.map(_.toCurrentGroupHeader).toSeq :+ tokenHeader
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
