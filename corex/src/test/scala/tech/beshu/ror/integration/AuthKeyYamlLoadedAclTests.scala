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

import java.time.Clock
import java.util.Base64

import eu.timepit.refined.types.string.NonEmptyString
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Inside, WordSpec}
import org.scalatest.Matchers._
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockRequestContext}
import tech.beshu.ror.acl.{Acl, AclActionHandler, ResponseWriter}
import tech.beshu.ror.acl.factory.{CoreFactory, CoreSettings}
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.Matched
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.acl.AclHandlingResult.Result.Success
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.acl.domain.Header.Name
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}

class AuthKeyYamlLoadedAclTests extends WordSpec with MockFactory with Inside {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new CoreFactory
  }
  private val acl: Acl = factory
    .createCoreFrom(
      """
        |other_non_ror_settings:
        |
        |  sth: "sth"
        |
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
      """.stripMargin,
      MockHttpClientsFactory
    )
    .map {
      case Left(err) => throw new IllegalStateException(s"Cannot create ACL: $err")
      case Right(CoreSettings(aclEngine, _, _)) => aclEngine
    }
    .runSyncUnsafe()

  "An ACL" when {
    "two blocks with auth_keys are configured" should {
      "allow to proceed" when {
        "request is sent in behalf on admin user" in {
          val responseWriter = mock[ResponseWriter]
          (responseWriter.writeResponseHeaders _).expects(*).returning({})
          (responseWriter.commit _).expects().returning({})
          val handler = mock[AclActionHandler]
          (handler.onAllow _).expects(*).returning(responseWriter)
          val request = MockRequestContext.default.copy(headers = Set(basicAuthHeader("admin:container")))

          val result = acl.handle(request, handler).runSyncUnsafe()
          result.history should have size 1
          inside(result.handlingResult) { case Success(Matched(block, _)) =>
            block.name should be(Block.Name("CONTAINER ADMIN"))
          }
        }
        "request is sent in behalf of admin user, but the authorization token header is lower case string" in {
          val responseWriter = mock[ResponseWriter]
          (responseWriter.writeResponseHeaders _).expects(*).returning({})
          (responseWriter.commit _).expects().returning({})
          val handler = mock[AclActionHandler]
          (handler.onAllow _).expects(*).returning(responseWriter)
          val request = MockRequestContext.default.copy(headers = Set(
            Header(
              Name(NonEmptyString.unsafeFrom("authorization")),
              NonEmptyString.unsafeFrom("Basic " + Base64.getEncoder.encodeToString("admin:container".getBytes))
            )
          ))

          val result = acl.handle(request, handler).runSyncUnsafe()
          result.history should have size 1
          inside(result.handlingResult) { case Success(Matched(block, _)) =>
            block.name should be(Block.Name("CONTAINER ADMIN"))
          }
        }
      }
    }
  }
}
