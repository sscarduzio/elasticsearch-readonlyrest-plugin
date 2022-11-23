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

import eu.timepit.refined.auto._
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.RorKbnAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.RorKbnAuthRule.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.misc.JwtUtils._
import tech.beshu.ror.utils.misc.Random
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import java.security.Key
import scala.concurrent.duration._
import scala.language.postfixOps

class RorKbnAuthRuleTests
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion {

  "A RorKbnAuthRule" should {
    "match" when {
      "token has valid HS256 signature" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
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
              jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
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
              jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "groups claim name is defined and no groups field is passed in token claim" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
        ))
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = Groups.NotDefined,
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "rule groups are defined and intersection between those groups and Ror Kbn ones is not empty (no preferred group)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = Groups.Defined(
            GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("group3"), GroupName("group2")),
            ))
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupName("group2")),
              availableGroups = UniqueList.of(GroupName("group2")),
              jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "rule groups are defined and intersection between those groups and Ror Kbn ones is not empty (with preferred group)" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = Groups.Defined(
            GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("group3"), GroupName("group2"))
            ))
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroup = Some(GroupName("group2"))
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupName("group2")),
              availableGroups = UniqueList.of(GroupName("group2")),
              jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
            )(blockContext)
        }
      }
      "groups OR logic is used" when {
        "at least one allowed group matches the JWT groups (1)" in {
          val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
          val jwt = Jwt(key, claims = List(
            "user" := "user1",
            "groups" := List("group1", "group2")
          ))
          assertMatchRule(
            configuredRorKbnDef = RorKbnDef(
              RorKbnDef.Name("test"),
              SignatureCheckMethod.Hmac(key.getEncoded)
            ),
            configuredGroups = Groups.Defined(
              GroupsLogic.Or(PermittedGroups(
                UniqueNonEmptyList.of(GroupName("group3"), GroupName("group2")),
              ))
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                currentGroup = Some(GroupName("group2")),
                availableGroups = UniqueList.of(GroupName("group2")),
                jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
              )(blockContext)
          }
        }
        "at least one allowed group matches the JWT groups (2)" in {
          val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
          val jwt = Jwt(key, claims = List(
            "user" := "user1",
            "groups" := List("group1", "group2")
          ))
          assertMatchRule(
            configuredRorKbnDef = RorKbnDef(
              RorKbnDef.Name("test"),
              SignatureCheckMethod.Hmac(key.getEncoded)
            ),
            configuredGroups = Groups.Defined(
              GroupsLogic.Or(PermittedGroups(
                UniqueNonEmptyList.of(GroupLike.from("*3"), GroupLike.from("*2")),
              ))
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                currentGroup = Some(GroupName("group2")),
                availableGroups = UniqueList.of(GroupName("group2")),
                jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
              )(blockContext)
          }
        }
      }
      "groups AND logic is used" when {
        "all allowed groups match the JWT groups (1)" in {
          val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
          val jwt = Jwt(key, claims = List(
            "user" := "user1",
            "groups" := List("group1", "group2", "group3")
          ))
          assertMatchRule(
            configuredRorKbnDef = RorKbnDef(
              RorKbnDef.Name("test"),
              SignatureCheckMethod.Hmac(key.getEncoded)
            ),
            configuredGroups = Groups.Defined(
              GroupsLogic.And(PermittedGroups(
                UniqueNonEmptyList.of(GroupName("group3"), GroupName("group2")),
              ))
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                currentGroup = Some(GroupName("group3")),
                availableGroups = UniqueList.of(GroupName("group3"), GroupName("group2")),
                jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
              )(blockContext)
          }
        }
        "all allowed groups match the JWT groups (2)" in {
          val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
          val jwt = Jwt(key, claims = List(
            "user" := "user1",
            "groups" := List("group1", "group2", "group3")
          ))
          assertMatchRule(
            configuredRorKbnDef = RorKbnDef(
              RorKbnDef.Name("test"),
              SignatureCheckMethod.Hmac(key.getEncoded)
            ),
            configuredGroups = Groups.Defined(
              GroupsLogic.And(PermittedGroups(
                UniqueNonEmptyList.of(GroupLike.from("*3"), GroupLike.from(("*2"))),
              ))
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                currentGroup = Some(GroupName("group3")),
                availableGroups = UniqueList.of(GroupName("group3"), GroupName("group2")),
                jwt = Some(JwtTokenPayload(jwt.defaultClaims()))
              )(blockContext)
          }
        }
      }
    }
    "not match" when {
      "token has invalid HS256 signature" in {
        val key1: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val key2: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
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
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
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
      "groups aren't passed in JWT token claim while some groups are defined in settings" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "userGroups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = Groups.Defined(
            GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("g1"))
            ))
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "rule groups are defined with 'or' logic and intersection between those groups and ROR Kbn ones is empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = Groups.Defined(
            GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("group3"), GroupName("group4")),
            ))
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "rule groups are defined with 'and' logic and intersection between those groups and ROR Kbn ones is empty" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = Groups.Defined(
            GroupsLogic.And(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("group2"), GroupName("group3")),
            ))
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "preferred group is not on the groups list from JWT" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroup = Some(GroupName("group5"))
        )
      }
      "preferred group is not on the permitted groups list" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val jwt = Jwt(key, claims = List(
          "user" := "user1",
          "groups" := List("group5", "group2")
        ))
        assertNotMatchRule(
          configuredRorKbnDef = RorKbnDef(
            RorKbnDef.Name("test"),
            SignatureCheckMethod.Hmac(key.getEncoded)
          ),
          configuredGroups = Groups.Defined(
            GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("group3"), GroupName("group2"))
            ))
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroup = Some(GroupName("group5"))
        )
      }
    }
  }

  private def assertMatchRule(configuredRorKbnDef: RorKbnDef,
                              configuredGroups: Groups = Groups.NotDefined,
                              tokenHeader: Header,
                              preferredGroup: Option[GroupName] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredRorKbnDef, configuredGroups, tokenHeader, preferredGroup, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredRorKbnDef: RorKbnDef,
                                 configuredGroups: Groups = Groups.NotDefined,
                                 tokenHeader: Header,
                                 preferredGroup: Option[GroupName] = None): Unit =
    assertRule(configuredRorKbnDef, configuredGroups, tokenHeader, preferredGroup, blockContextAssertion = None)

  private def assertRule(configuredRorKbnDef: RorKbnDef,
                         configuredGroups: Groups = Groups.NotDefined,
                         tokenHeader: Header,
                         preferredGroup: Option[GroupName],
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new RorKbnAuthRule(RorKbnAuthRule.Settings(configuredRorKbnDef, configuredGroups), UserIdEq.caseSensitive)
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
}
