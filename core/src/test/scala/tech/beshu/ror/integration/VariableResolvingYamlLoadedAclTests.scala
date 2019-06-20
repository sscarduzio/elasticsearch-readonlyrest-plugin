package tech.beshu.ror.integration

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.domain.{LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

class VariableResolvingYamlLoadedAclTests extends WordSpec with BaseYamlLoadedAclTest with MockFactory with Inside {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |   - name: "CONTAINER ADMIN"
      |     type: allow
      |     auth_key: admin:container
      |
      |   - name: "Group name from header"
      |     type: allow
      |     groups: ["g4", "@{X-my-group-name-1}", "@{header:X-my-group-name-2}" ]
      |
      |  users:
      |  - username: user
      |    auth_key: user:passwd
      |    groups: ["g1", "g2", "g3"]
    """.stripMargin

  "An ACL" when {
    "is configured using config above" should {
      "allow to proceed" when {
        "old style header variable is used" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user:passwd"), header("X-my-group-name-1", "g3"))
          )

          val result = acl.handle(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header"))
            assertBlockContext(
              loggedUser = Some(LoggedUser(User.Id("user"))),
              currentGroup = Some(groupFrom("g3")),
              availableGroups = Set(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"))
            ) {
              blockContext
            }
          }
        }
        "new style header variable is used" in {
          val request = MockRequestContext.default.copy(
            headers = Set(basicAuthHeader("user:passwd"), header("X-my-group-name-2", "g3"))
          )

          val result = acl.handle(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
            block.name should be(Block.Name("Group name from header"))
            assertBlockContext(
              loggedUser = Some(LoggedUser(User.Id("user"))),
              currentGroup = Some(groupFrom("g3")),
              availableGroups = Set(groupFrom("g1"), groupFrom("g2"), groupFrom("g3"))
            ) {
              blockContext
            }
          }
        }
      }
    }
  }
}
