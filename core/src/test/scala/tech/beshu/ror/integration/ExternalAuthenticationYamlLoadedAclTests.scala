package tech.beshu.ror.integration

import java.time.Clock

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.TestsUtils.basicAuthHeader
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.Matched
import tech.beshu.ror.acl.{Acl, AclHandler, ResponseWriter}
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, RorAclFactory}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockRequestContext}
import monix.execution.Scheduler.Implicits.global

class ExternalAuthenticationYamlLoadedAclTests extends WordSpec with MockFactory with Inside {
  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new RorAclFactory
  }
  private val acl: Acl = factory.createAclFrom(
    """
      |http.host: 0.0.0.0
      |
      |http.type: ssl_netty4
      |#transport.type: local
      |readonlyrest:
      |  ssl:
      |    enable: true
      |    keystore_file: "keystore.jks"
      |    keystore_pass: readonlyrest
      |    key_pass: readonlyrest
      |
      |  # (De)activate plugin
      |  enable: true
      |
      |  # HTTP response body in case of forbidden request.
      |  # If this is null or omitted, the name of the first violated access control rule is returned (useful for debugging!)
      |  response_if_req_forbidden: <h1>Forbidden</h1>
      |
      |  # Default policy is to forbid everything, so let's define a whitelist
      |  access_control_rules:
      |
      |  # ES container initializer need this rule to configure ES instance after startup
      |  - name: "CONTAINER ADMIN"
      |    type: allow
      |    auth_key: admin:container
      |
      |  - name: "::Tweets::"
      |    type: allow
      |    methods: GET
      |    indices: ["twitter"]
      |    external_authentication: "ext1"
      |
      |  - name: "::Facebook posts::"
      |    type: allow
      |    methods: GET
      |    indices: ["facebook"]
      |    external_authentication:
      |      service: "ext2"
      |      cache_ttl_in_sec: 60
      |
      |  external_authentication_service_configs:
      |
      |  - name: "ext1"
      |    authentication_endpoint: "http://localhost:8080/auth1"
      |    success_status_code: 200
      |    cache_ttl_in_sec: 60
      |
      |  - name: "ext2"
      |    authentication_endpoint: "http://localhost:8080/auth2"
      |    success_status_code: 204
      |    cache_ttl_in_sec: 60
      |
    """.stripMargin,
    new AsyncHttpClientsFactory
  ) match {
    case Left(err) => throw new IllegalStateException(s"Cannot create ACL: $err")
    case Right(createdAcl) => createdAcl
  }

  "A test" in {
    val responseWriter = mock[ResponseWriter]
    (responseWriter.writeResponseHeaders _).expects(*).returning({})
    val handler = mock[AclHandler]
    (handler.onAllow _).expects(*).returning(responseWriter)
    val request = MockRequestContext.default.copy(headers = Set(basicAuthHeader("cartman:user1")))

    val (history, result) = acl.handle(request, handler).runSyncUnsafe()
    history should have size 1
    inside(result) { case Matched(block, _) =>
      block.name should be(Block.Name("CONTAINER ADMIN"))
    }
  }
}
