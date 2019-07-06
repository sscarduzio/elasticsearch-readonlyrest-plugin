package tech.beshu.ror.integration

import java.util.Base64

import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Jwts
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.domain.{Header, IndexName, LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.utils.TestsUtils
import tech.beshu.ror.utils.TestsUtils._

import scala.collection.JavaConverters._

class VariableResolvingYamlLoadedAclTests extends WordSpec with BaseYamlLoadedAclTest with MockFactory with Inside {

  private lazy val (pub, secret) = TestsUtils.generateRsaRandomKeys
  override protected def configYaml: String =
    s"""
      |readonlyrest:
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
      |   - name: "Group name from jwt variable"
      |     type: allow
      |     jwt_auth:
      |       name: "jwt1"
      |     indices: ["g@explode{jwt:tech.beshu.mainGroup}"]
      |
      |  users:
      |   - username: user1
      |     auth_key: user1:passwd
      |     groups: ["g1", "g2", "g3", "gs1"]
      |
      |   - username: user2
      |     auth_key: user2:passwd
      |     groups: ["g1", "g2", "g3", "gs2"]
      |
      |   - username: user3
      |     jwt_auth: jwt1
      |     groups: ["g1", "g2", "g3", "gj2"]
      |
      |  jwt:
      |
      |  - name: jwt1
      |    signature_algo: "RSA"
      |    signature_key: "${Base64.getEncoder.encodeToString(pub.getEncoded)}"
      |    user_claim: "userId"
      |    groups_claim: "tech.beshu.mainGroup"
    """.stripMargin

  "An ACL" when {
    "is configured using config above" should {
      "allow to proceed" when {
        "old style header variable is used" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-name-1", "g3"))
          )

          val result = acl.handle(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header variable"))
            assertBlockContext(
              loggedUser = Some(LoggedUser(User.Id("user1"))),
              currentGroup = Some(groupFrom("g3")),
              availableGroups = allUser1Groups
            ) {
              blockContext
            }
          }
        }
        "new style header variable is used" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user1:passwd"), header("X-my-group-name-2", "g3"))
          )

          val result = acl.handle(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header variable"))
            assertBlockContext(
              loggedUser = Some(LoggedUser(User.Id("user1"))),
              currentGroup = Some(groupFrom("g3")),
              availableGroups = allUser1Groups
            ) {
              blockContext
            }
          }
        }
        "old style of env variable is used" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user2:passwd"))
          )

          val result = acl.handle(request).runSyncUnsafe()

          result.history should have size 4
          inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from env variable (old syntax)"))
            assertBlockContext(
              loggedUser = Some(LoggedUser(User.Id("user2"))),
              currentGroup = Some(groupFrom("gs2")),
              availableGroups = allUser2Groups
            ) {
              blockContext
            }
          }
        }
        "new style of env variable is used" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user1:passwd"))
          )

          val result = acl.handle(request).runSyncUnsafe()

          result.history should have size 3
          inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from env variable"))
            assertBlockContext(
              loggedUser = Some(LoggedUser(User.Id("user1"))),
              currentGroup = Some(groupFrom("gs1")),
              availableGroups = allUser1Groups
            ) {
              blockContext
            }
          }
        }
        "JWT variable is used" in {
          val request = MockRequestContext.default.copy(
            headers = Set(Header(
              Header.Name.authorization,
              {
                val jwtBuilder = Jwts.builder
                  .signWith(secret)
                  .setSubject("test")
                  .claim("userId", "user3")
                  .claim("tech", Map("beshu" -> Map("mainGroup" -> "j1,j2").asJava).asJava)
                NonEmptyString.unsafeFrom(s"Bearer ${jwtBuilder.compact}")
              })),
            indices = Set(IndexName("gj1")),
            involvesIndices = true
          )

          val result = acl.handle(request).runSyncUnsafe()

          result.history should have size 5
          inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from jwt variable"))
            assertBlockContext(
              loggedUser = Some(LoggedUser(User.Id("user3")))
            ) {
              blockContext
            }
          }
        }
      }
    }
  }

  override implicit protected def envVarsProvider: EnvVarsProvider = {
    case EnvVarName(n) if n.value == "sys_group_1" => Some("s1")
    case EnvVarName(n) if n.value == "sys_group_2" => Some("s2")
    case _ => None
  }

  private val allUser1Groups =
    Set(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"), groupFrom("gs1"))

  private val allUser2Groups =
    Set(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"), groupFrom("gs2"))

  private val allUser3Groups =
    Set(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"), groupFrom("gj1"))
}
