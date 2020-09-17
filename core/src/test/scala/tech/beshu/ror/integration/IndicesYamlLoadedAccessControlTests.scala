package tech.beshu.ror.integration

import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.{Allow, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

class IndicesYamlLoadedAccessControlTests  extends WordSpec with BaseYamlLoadedAccessControlTest with Inside {
  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Forbidden for .readonlyrest index"
      |    type: "allow"
      |    indices:
      |      patterns: [".readonlyrest"]
      |      must_involve_indices: true #( <true|false|any> normal behaviour without option = any)
      |
    """.stripMargin

  "An ACL" when {
    "indices rule is defined with must_involve_indices: true flag" should {
      "allow to proceed" when {
        "it is an indices request and the requested index is on the configured list" in {
          val request = MockRequestContext.indices.copy(indices = Set(IndexName(".readonlyrest".nonempty)))
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case Allow(_, _) => }
        }
      }
      "not allow to proceed" when {
        "it is not an indices request" in {
          val request = MockRequestContext.metadata
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case ForbiddenByMismatched(_) => }
        }
      }
    }
  }
}
