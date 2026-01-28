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
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.{GroupsConfig, SignatureCheckMethod}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.Result.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{JwtAuthRule, JwtAuthenticationRule, JwtAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.{GroupId, GroupIdPattern}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Jwt as _, *}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.misc.JwtUtils.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import java.security.Key
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

class JwtAuthRuleTests
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion with WithDummyRequestIdSupport {

  "A JwtAuthRule" should {
    "match" when {
      "user claim name is defined and userId is passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2"),
        ))
        assertMatchRule(
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None),
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group1")),
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupId("group1")),
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("https://{domain}/claims/roles")), None)
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group1"))
            )(blockContext)
        }
      }
      "group IDs claim name is defined and no groups field is passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertMatchRule(
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          configuredGroups = None,
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupId("group1")),
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("tech.beshu.groups")), None)
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              currentGroup = Some(GroupId("group1")), //RORDEV-1639 - this behavior changed, the `currentGroup` was not added to the context in pseudo-authorization before
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
          ),
          tokenHeader = bearerHeader(jwt)
        ) {
          blockContext =>
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
              jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
              currentGroup = Some(GroupId("group1")), //RORDEV-1639 - this behavior changed, the `currentGroup` was not added to the context in pseudo-authorization before
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
            configuredJwtDef = AuthJwtDef(
              JwtDef.Name("test"),
              AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
              SignatureCheckMethod.Hmac(key.getEncoded),
              userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
              groupsConfig = GroupsConfig(
                idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
                namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
              )
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
                currentGroup = Some(GroupId("group1")), //RORDEV-1639 - this behavior changed, the `currentGroup` was not added to the context in pseudo-authorization before
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
            configuredJwtDef = AuthJwtDef(
              JwtDef.Name("test"),
              AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
              SignatureCheckMethod.Hmac(key.getEncoded),
              userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
              groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")), Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name"))))
            ),
            tokenHeader = bearerHeader(jwt)
          ) {
            blockContext =>
              assertBlockContext(
                loggedUser = Some(DirectlyLoggedUser(User.Id("user1"))),
                jwt = Some(domain.Jwt.Payload(jwt.defaultClaims())),
                currentGroup = Some(GroupId("group1")), //RORDEV-1639 - this behavior changed, the `currentGroup` was not added to the context in pseudo-authorization before
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.id)].id")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("groups[?(@.name)].name")))
            )
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
    }
    "not match" when {
      "preferred group is not on the groups list from JWT" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          tokenHeader = bearerHeader(jwt),
          preferredGroupId = Some(GroupId("group3"))
        )
      }
      "user claim name is defined but userId isn't passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "group IDs claim name is defined but groups aren't passed in JWT token claim" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("tech.beshu.groups.subgroups")), None)
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
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
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
          ),
          configuredGroups = Some(GroupsLogic.AllOf(GroupIds(
            UniqueNonEmptyList.of(GroupId("group2"), GroupId("group3"))
          ))),
          tokenHeader = bearerHeader(jwt)
        )
      }
      "preferred group is not on the permitted groups list" in {
        val key: Key = Jwts.SIG.HS256.key().build()
        val jwt = Jwt(key, claims = List(
          "userId" := "user1",
          "groups" := List("group1", "group2")
        ))
        assertNotMatchRule(
          configuredJwtDef = AuthJwtDef(
            JwtDef.Name("test"),
            AuthorizationTokenDef(Header.Name.authorization, "Bearer "),
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = domain.Jwt.ClaimName(jsonPathFrom("userId")),
            groupsConfig = GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)
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

  private def assertMatchRule(configuredJwtDef: AuthJwtDef,
                              configuredGroups: Option[GroupsLogic] = None,
                              tokenHeader: Header,
                              preferredGroupId: Option[GroupId] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroupId, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: AuthJwtDef,
                                 configuredGroups: Option[GroupsLogic] = None,
                                 tokenHeader: Header,
                                 preferredGroupId: Option[GroupId] = None): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, preferredGroupId, blockContextAssertion = None)

  private def assertRule(configuredJwtDef: AuthJwtDef,
                         configuredGroups: Option[GroupsLogic],
                         tokenHeader: Header,
                         preferredGroup: Option[GroupId],
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val groupsLogic = configuredGroups.getOrElse(
      GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdPattern.fromNes(nes("*")))))
    )
    val authzSettings = JwtAuthorizationRule.Settings(
      AuthorizationJwtDef(configuredJwtDef.id, configuredJwtDef.authorizationTokenDef, configuredJwtDef.checkMethod, configuredJwtDef.groupsConfig),
      groupsLogic,
    )
    val authnSettings = JwtAuthenticationRule.Settings(
      AuthenticationJwtDef(configuredJwtDef.id, configuredJwtDef.authorizationTokenDef, configuredJwtDef.checkMethod, configuredJwtDef.userClaim)
    )
    val rule = new JwtAuthRule(
      new JwtAuthenticationRule(authnSettings, CaseSensitivity.Enabled),
      new JwtAuthorizationRule(authzSettings),
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
      allAllowedIndices = Set.empty,
      allAllowedClusters = Set.empty
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
