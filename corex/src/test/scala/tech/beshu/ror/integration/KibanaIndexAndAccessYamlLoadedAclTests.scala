package tech.beshu.ror.integration

import java.time.Clock

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.Matched
import tech.beshu.ror.acl.factory.{CoreFactory, CoreSettings}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.acl.{Acl, AclHandler, ResponseWriter}
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockRequestContext}

class KibanaIndexAndAccessYamlLoadedAclTests extends WordSpec with MockFactory with Inside {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new CoreFactory
  }
  private val acl: Acl = factory
    .createCoreFrom(
      """
        |readonlyrest:
        |
        |  access_control_rules:
        |
        |  - name: "Template Tenancy"
        |    verbosity: error
        |    kibana_access: admin
        |    kibana_index: ".kibana_template"
        |
      """.stripMargin,
      MockHttpClientsFactory
    )
    .map {
      case Left(err) => throw new IllegalStateException(s"Cannot create ACL: $err")
      case Right(CoreSettings(aclEngine, _, _)) => aclEngine
    }
    .runSyncUnsafe()


  "An ACL" when {
    "kibana index and kibana access rules are used" should {
      "allow to proceed" in {
        val responseWriter = mock[ResponseWriter]
        (responseWriter.writeResponseHeaders _)
          .expects(Map(
            "x-ror-kibana_access" -> "admin",
            "x-ror-kibana_index" -> ".kibana_template"
          ))
          .returning({})
        (responseWriter.commit _).expects().returning({})
        val handler = mock[AclHandler]
        (handler.onAllow _).expects(*).returning(responseWriter)
        val request = MockRequestContext.default

        val (history, result) = acl.handle(request, handler).runSyncUnsafe()
        history should have size 1
        inside(result) { case Matched(block, _) =>
          block.name should be(Block.Name("Template Tenancy"))
        }
      }
    }
  }
}
