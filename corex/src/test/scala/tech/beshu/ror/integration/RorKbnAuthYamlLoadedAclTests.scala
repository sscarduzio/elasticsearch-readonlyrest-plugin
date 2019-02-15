package tech.beshu.ror.integration

import java.time.Clock

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.Matched
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, CoreSettings, RorAclFactory}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.acl.{Acl, AclHandler, ResponseWriter}
import tech.beshu.ror.mocks.MockRequestContext

class RorKbnAuthYamlLoadedAclTests extends WordSpec with MockFactory with Inside {
  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new RorAclFactory
  }
  private val acl: Acl = factory.createCoreFrom(
    """http.bind_host: _eth0:ipv4_
      |network.host: _eth0:ipv4_
      |
      |http.type: ssl_netty4
      |#transport.type: local
      |
      |readonlyrest:
      |  ssl:
      |    enable: true
      |    keystore_file: "keystore.jks"
      |    keystore_pass: readonlyrest
      |    key_pass: readonlyrest
      |
      |  access_control_rules:
      |    - name: Container housekeeping is allowed
      |      type: allow
      |      auth_key: admin:container
      |
      |    - name: Valid JWT token is present
      |      type: allow
      |      ror_kbn_auth:
      |        name: "kbn1"
      |
      |    - name: Valid JWT token is present with another key
      |      type: allow
      |      ror_kbn_auth:
      |        name: "kbn2"
      |
      |    - name: Valid JWT token is present with a third key + role
      |      type: allow
      |      ror_kbn_auth:
      |        name: "kbn3"
      |        roles: ["viewer_group"]
      |
      |  ror_kbn:
      |
      |    - name: kbn1
      |      signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
      |
      |    - name: kbn2
      |      signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
      |
      |    - name: kbn3
      |      signature_key: "1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890"
      |
    """.stripMargin,
    new AsyncHttpClientsFactory
  ) match {
    case Left(err) => throw new IllegalStateException(s"Cannot create ACL: $err")
    case Right(CoreSettings(aclEngine, _, _)) => aclEngine
  }

  "A ACL" when {
    "is configured using config above" should {
      "allow to proceed" when {
        "JWT token with empty list of groups is defined" in {
          val jwtBuilder = Jwts.builder
            .signWith(Keys.hmacShaKeyFor("123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456".getBytes))
            .setSubject("test")
            .claim("groups", "")
            .claim("user", "user")

          val responseWriter = mock[ResponseWriter]
          (responseWriter.writeResponseHeaders _).expects(*).returning({})
          (responseWriter.commit _).expects().returning({})
          val handler = mock[AclHandler]
          (handler.onAllow _).expects(*).returning(responseWriter)
          val request = MockRequestContext.default.copy(headers = Set(header("Authorization", s"Bearer ${jwtBuilder.compact}")))

          val (history, result) = acl.handle(request, handler).runSyncUnsafe()
          history should have size 2
          inside(result) { case Matched(block, _) =>
            block.name should be(Block.Name("Valid JWT token is present"))
          }
        }
      }
    }
  }
}
