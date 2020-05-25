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
package tech.beshu.ror.integration

import java.util.Base64

import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.impl.DefaultClaims
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{GeneralIndexRequestBlockContext, SearchRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.utils.TestsUtils
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.collection.JavaConverters._

class VariableResolvingYamlLoadedAccessControlTests extends WordSpec
  with BaseYamlLoadedAccessControlTest with MockFactory with Inside {

  private lazy val (pub, secret) = TestsUtils.generateRsaRandomKeys

  override protected def configYaml: String =
    s"""
       |readonlyrest:
       |
       |  enable: $${READONLYREST_ENABLE}
       |
       |  access_control_rules:
       |   - name: "CONTAINER ADMIN"
       |     type: allow
       |     auth_key: admin:container
       |
       |   - name: "Group name from header variable"
       |     type: allow
       |     groups: ["g4", "@{X-my-group-name-1}", "@{header:X-my-group-name-2}" ]
       |
       |   - name: "Group name from env variable"
       |     type: allow
       |     groups: ["g@{env:sys_group_1}"]
       |
       |   - name: "Group name from env variable (old syntax)"
       |     type: allow
       |     groups: ["g$${sys_group_2}"]
       |
       |   - name: "Variables usage in filter"
       |     type: allow
       |     filter: '{"bool": { "must": { "terms": { "user_id": [@{jwt:user_id_list}] }}}}'
       |     jwt_auth: "jwt3"
       |     users: ["user5"]
       |
       |   - name: "Group name from jwt variable (array)"
       |     type: allow
       |     jwt_auth:
       |       name: "jwt1"
       |     indices: ["g@explode{jwt:tech.beshu.mainGroup}"]
       |
       |   - name: "Group name from jwt variable"
       |     type: allow
       |     jwt_auth:
       |       name: "jwt2"
       |     indices: ["g@explode{jwt:tech.beshu.mainGroupsString}"]
       |
       |  users:
       |   - username: user1
       |     auth_key: $${USER1_PASS}
       |     groups: ["g1", "g2", "g3", "gs1"]
       |
       |   - username: user2
       |     auth_key: user2:passwd
       |     groups: ["g1", "g2", "g3", "gs2"]
       |
       |  jwt:
       |
       |  - name: jwt1
       |    signature_algo: "RSA"
       |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |    user_claim: "userId"
       |    groups_claim: "tech.beshu.mainGroup"
       |
       |  - name: jwt2
       |    signature_algo: "RSA"
       |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |    user_claim: "userId"
       |    groups_claim: "tech.beshu.mainGroupsString"
       |
       |  - name: jwt3
       |    signature_algo: "RSA"
       |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
       |    user_claim: "userId"
       |    groups_claim: "tech.beshu.mainGroupsString"
    """.stripMargin

  "An ACL" when {
    "is configured using config above" should {
      "allow to proceed" when {
        "old style header variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-name-1", "g3"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              currentGroup = Some(groupFrom("g3")),
              availableGroups = UniqueList.of(groupFrom("g3"))
            ) {
              blockContext
            }
          }
        }
        "new style header variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-name-2", "g3"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              currentGroup = Some(groupFrom("g3")),
              availableGroups = UniqueList.of(groupFrom("g3"))
            ) {
              blockContext
            }
          }
        }
        "old style of env variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user2:passwd"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 4
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from env variable (old syntax)"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user2".nonempty))),
              currentGroup = Some(groupFrom("gs2")),
              availableGroups = UniqueList.of(groupFrom("gs2"))
            ) {
              blockContext
            }
          }
        }
        "new style of env variable is used" in {
          val request = MockRequestContext.indices.copy(
            headers = Set(basicAuthHeader("user1:passwd"))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 3
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from env variable"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              currentGroup = Some(groupFrom("gs1")),
              availableGroups = UniqueList.of(groupFrom("gs1"))
            ) {
              blockContext
            }
          }
        }
        "JWT variable is used (array)" in {
          val claims = new DefaultClaims(Map[String, AnyRef](
            "sub" -> "test",
            "userId" -> "user3",
            "tech" -> Map("beshu" -> Map("mainGroup" -> List("j1", "j2").asJava).asJava).asJava
          ).asJava)
          val request = MockRequestContext.indices.copy(
            headers = Set(new Header(
              Header.Name.authorization,
              {
                val jwtBuilder = Jwts.builder.signWith(secret).setSubject("test").setClaims(claims)
                NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
              })),
            indices = Set(IndexName("gj1".nonempty))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 6
          inside(result.result) { case RegularRequestResult.Allow(blockContext: GeneralIndexRequestBlockContext, block) =>
            block.name should be(Block.Name("Group name from jwt variable (array)"))
            blockContext.userMetadata should be(
              UserMetadata
                .empty
                .withLoggedUser(DirectlyLoggedUser(User.Id("user3".nonempty)))
                .withJwtToken(JwtTokenPayload(claims))
            )
            blockContext.indices should be(Set(IndexName("gj1".nonempty)))
            blockContext.contextHeaders should be(Set.empty)
            blockContext.responseHeaders should be(Set.empty)
          }
        }
        "JWT variable is used (CSV string)" in {
          val claims = new DefaultClaims(Map[String, AnyRef](
            "sub" -> "test",
            "userId" -> "user4",
            "tech" -> Map("beshu" -> Map("mainGroupsString" -> "j0,j3").asJava).asJava
          ).asJava)
          val request = MockRequestContext.indices.copy(
            headers = Set(new Header(
              Header.Name.authorization,
              {
                val jwtBuilder = Jwts.builder.signWith(secret).setSubject("test").setClaims(claims)
                NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
              })),
            indices = Set(IndexName("gj0".nonempty)),
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("gj0".nonempty), Set.empty))
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 7
          inside(result.result) { case RegularRequestResult.Allow(blockContext: GeneralIndexRequestBlockContext, block) =>
            block.name should be(Block.Name("Group name from jwt variable"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("user4".nonempty)))
                .withJwtToken(JwtTokenPayload(claims))
            )
            blockContext.indices should be(Set(IndexName("gj0".nonempty)))
            blockContext.contextHeaders should be(Set.empty)
            blockContext.responseHeaders should be(Set.empty)
          }
        }
        "JWT variable in filter query is used" in {
          val claims = new DefaultClaims(Map[String, AnyRef](
            "sub" -> "test",
            "userId" -> "user5",
            "user_id_list" -> List("alice", "bob").asJava
          ).asJava)
          val request = MockRequestContext.search.copy(
            headers = Set(new Header(
              Header.Name.authorization,
              {
                val jwtBuilder = Jwts.builder.signWith(secret).setSubject("test").setClaims(claims)
                NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
              })),
            indices = Set.empty,
            allIndicesAndAliases = Set.empty
          )

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 5
          inside(result.result) { case RegularRequestResult.Allow(blockContext: SearchRequestBlockContext, block) =>
            block.name should be(Block.Name("Variables usage in filter"))
            blockContext.userMetadata should be(
              UserMetadata
                .from(request)
                .withLoggedUser(DirectlyLoggedUser(User.Id("user5".nonempty)))
                .withJwtToken(JwtTokenPayload(claims))
            )
            blockContext.indices should be(Set.empty)
            blockContext.responseHeaders should be(Set.empty)
            blockContext.filter should be(Some(Filter("""{"bool": { "must": { "terms": { "user_id": ["alice","bob"] }}}}""".nonempty)))
          }
        }
      }
    }
  }

  override implicit protected def envVarsProvider: EnvVarsProvider = {
    case EnvVarName(n) if n.value == "sys_group_1" => Some("s1")
    case EnvVarName(n) if n.value == "sys_group_2" => Some("s2")
    case EnvVarName(n) if n.value == "READONLYREST_ENABLE" => Some("true")
    case EnvVarName(n) if n.value == "USER1_PASS" => Some("user1:passwd")
    case _ => None
  }
}
