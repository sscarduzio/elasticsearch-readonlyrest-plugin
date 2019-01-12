package tech.beshu.ror.integration

import org.scalamock.scalatest.MockFactory
import org.scalatest.{Inside, WordSpec}
import org.scalatest.Matchers._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.{AclHandler, ResponseWriter}
import tech.beshu.ror.unit.acl.factory.RorAclFactory
import tech.beshu.ror.TestsUtils.basicAuthHeader
import tech.beshu.ror.unit.acl.blocks.Block
import tech.beshu.ror.unit.acl.blocks.Block.ExecutionResult.Matched
import monix.execution.Scheduler.Implicits.global

class YamlLoadedAclTests extends WordSpec with MockFactory with Inside {

  private val factory = new RorAclFactory

  "An ACL" when {
    "two blocks with auth_keys are configured" should {
      val acl = factory.createAclFrom(
        """
          |readonlyrest:
          |
          |  access_control_rules:
          |
          |  - name: "CONTAINER ADMIN"
          |    type: allow
          |    auth_key: admin:container
          |
          |  - name: "User 1"
          |    type: allow
          |    auth_key: user1:dev
        """.stripMargin
      ).right.get

      "allow to proceed" when {
        "request is sent in behalf on admin user" in {
          val responseWriter = mock[ResponseWriter]
          (responseWriter.writeResponseHeaders _).expects(*).returning({})
          val handler = mock[AclHandler]
          (handler.onAllow _).expects(*).returning(responseWriter)
          val request = MockRequestContext.default.copy(headers = Set(basicAuthHeader("admin:container")))

          val (history, result) = acl.handle(request, handler).runSyncUnsafe()
          history should have size 1
          inside(result) { case Matched(block, _) =>
            block.name should be (Block.Name("CONTAINER ADMIN"))
          }
        }
      }
    }
  }
}
