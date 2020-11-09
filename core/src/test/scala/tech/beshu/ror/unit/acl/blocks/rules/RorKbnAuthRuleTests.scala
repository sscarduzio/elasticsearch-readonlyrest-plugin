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

import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.{Jwts, SignatureAlgorithm}
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.rules.RorKbnAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils
import tech.beshu.ror.utils.TestsUtils._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps
import io.jsonwebtoken.impl.DefaultClaims
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.utils.uniquelist.UniqueList

class RorKbnAuthRuleTests
  extends WordSpec with MockFactory with Inside with BlockContextAssertion {

  "A RorKbnAuthRule" should {
    "match" when {
      "token has valid HS256 signature" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map("sub" -> "test", "user" -> "user1", "groups" -> List("group1", "group2").asJava).asJava)
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder.signWith(key).setClaims(claims)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              jwt = Some(JwtTokenPayload(claims))
            )(blockContext)
        }
      }
      "token has valid RS256 signature" in {
        val (pub, secret) = TestsUtils.generateRsaRandomKeys
        val claims = new DefaultClaims(Map("sub" -> "test", "user" -> "user1", "groups" -> List("group1", "group2").asJava).asJava)
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Rsa(pub)
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder.signWith(secret).setClaims(claims)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              jwt = Some(JwtTokenPayload(claims))
            )(blockContext)
        }
      }
      "groups claim name is defined and no groups field is passed in token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test", "user" -> "user1").asJava)
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Hmac(key.getEncoded)
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
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              jwt = Some(JwtTokenPayload(claims))
            )(blockContext)
        }
      }
      "rule groups are defined and intersection between those groups and Ror Kbn ones is not empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val claims = new DefaultClaims(Map("sub" -> "test", "user" -> "user1", "groups" -> List("group1", "group2").asJava).asJava)
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Hmac(key.getEncoded)
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
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              currentGroup = Some(Group("group2".nonempty)),
              availableGroups = UniqueList.of(Group("group2".nonempty)),
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
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Hmac(key1.getEncoded)
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder
                .signWith(key2)
                .setSubject("test")
                .claim("user", "user1")
                .claim("groups", List("group1", "group2").asJava)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        )
      }
      "token has invalid RS256 signature" in {
        val (pub, _) = TestsUtils.generateRsaRandomKeys
        val (_, secret) = TestsUtils.generateRsaRandomKeys
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Rsa(pub)
          ),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder
                .signWith(secret)
                .setSubject("test")
                .claim("user", "user1")
                .claim("groups", List("group1", "group2").asJava)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        )
      }
      "userId isn't passed in JWT token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
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
      "groups aren't passed in JWT token claim while some groups are defined in settings" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = UniqueList.of(Group("g1".nonempty)),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder
                .signWith(key)
                .setSubject("test")
                .claim("user", "user1")
                .claim("userGroups", List("group1", "group2").asJava)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        )
      }
      "rule groups are defined and intersection between those groups and ROR Kbn ones is empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test".nonempty),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = UniqueList.of(groupFrom("group3"), groupFrom("group4")),
          tokenHeader = new Header(
            Header.Name.authorization,
            {
              val jwtBuilder = Jwts.builder
                .signWith(key)
                .setSubject("test")
                .claim("user", "user1")
                .claim("groups", List("group1", "group2").asJava)
              NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
            }
          )
        )
      }
    }
  }

  private def assertMatchRule(configuredRorKbnDef: RorKbnDef,
                              configuredGroups: UniqueList[Group] = UniqueList.empty,
                              tokenHeader: Header)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredRorKbnDef, configuredGroups, tokenHeader, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredRorKbnDef: RorKbnDef,
                                 configuredGroups: UniqueList[Group] = UniqueList.empty,
                                 tokenHeader: Header): Unit =
    assertRule(configuredRorKbnDef, configuredGroups, tokenHeader, blockContextAssertion = None)

  private def assertRule(configuredRorKbnDef: RorKbnDef,
                         configuredGroups: UniqueList[Group] = UniqueList.empty,
                         tokenHeader: Header,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new RorKbnAuthRule(RorKbnAuthRule.Settings(configuredRorKbnDef, configuredGroups))
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
}
