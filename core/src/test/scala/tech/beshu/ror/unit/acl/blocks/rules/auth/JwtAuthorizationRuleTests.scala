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
import io.jsonwebtoken.Jwts
import org.scalamock.scalatest.MockFactory
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.GroupsAuthorizationFailed
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.{GroupsConfig, SignatureCheckMethod}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthorizationRule
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Jwt as _, *}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{RuleCheckAssertion, *}
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.misc.JwtUtils.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import java.security.Key
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

class JwtAuthorizationRuleTests
  extends AnyWordSpec with MockFactory with BlockContextAssertion with WithDummyRequestIdSupport {

  "A JwtAuthorizationRule" should {
    "match" when {
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
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
          ),
          configuredGroups = GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group3"), GroupId("group2"))
          )),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              currentGroup = Some(GroupId("group2")),
              availableGroups = UniqueList.of(group("group2", "Group 2")),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
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
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
          ),
          configuredGroups = GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group3"), GroupIdLike.from("*2"))
          )),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              currentGroup = Some(GroupId("group2")),
              availableGroups = UniqueList.of(group("group2", "Group 2")),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
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
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
          ),
          configuredGroups = GroupsLogic.AllOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group1"), GroupId("group2"))
          )),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              currentGroup = Some(GroupId("group1")),
              availableGroups = UniqueList.of(group("group1", "Group 1"), group("group2", "Group 2")),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
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
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
          ),
          configuredGroups = GroupsLogic.AllOf(GroupIds(
            UniqueNonEmptyList.of(GroupIdLike.from("*1"), GroupIdLike.from("*2"))
          )),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              currentGroup = Some(GroupId("group1")),
              availableGroups = UniqueList.of(group("group1", "Group 1"), group("group2", "Group 2")),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
            )(blockContext)
        }
      }
    }
    "not match" when {
      "group IDs claim path is wrong" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("tech.beshu.groups.subgroups")), None)
          ),
          configuredGroups = GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group1"))
          )),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = None,
          denialCause = GroupsAuthorizationFailed("Groups claim not found in JWT")
        )
      }
      "rule groups with 'or' logic are defined and intersection between those groups and JWT ones is empty" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          configuredGroups = GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group3"), GroupId("group4"))
          )),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = None,
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "rule groups with 'and' logic are defined and intersection between those groups and JWT ones is empty" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          configuredGroups = GroupsLogic.AllOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group2"), GroupId("group3"))
          )),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = None,
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "preferred group is not on the permitted groups list" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthorizationJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          configuredGroups = GroupsLogic.AnyOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group2"))
          )),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = Some(GroupId("group3")),
          denialCause = GroupsAuthorizationFailed("Current group is not allowed")
        )
      }
    }
  }

  private def assertMatchRule(configuredJwtDef: AuthorizationJwtDef,
                              configuredGroups: GroupsLogic,
                              tokenHeader: Header,
                              preferredGroupId: Option[GroupId] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroupId, RuleCheckAssertion.RulePermitted(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: AuthorizationJwtDef,
                                 configuredGroups: GroupsLogic,
                                 tokenHeader: Header,
                                 preferredGroupId: Option[GroupId],
                                 denialCause: Cause): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroupId, RuleCheckAssertion.RuleDenied(denialCause))

  private def assertRule(configuredJwtDef: AuthorizationJwtDef,
                         configuredGroups: GroupsLogic,
                         tokenHeader: Header,
                         preferredGroup: Option[GroupId],
                         assertion: RuleCheckAssertion): Unit = {
    val rule = new JwtAuthorizationRule(JwtAuthorizationRule.Settings(configuredJwtDef, configuredGroups))

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
    rule.checkAndAssert(blockContext, assertion)
  }
}
