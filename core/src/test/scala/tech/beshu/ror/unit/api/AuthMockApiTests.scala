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
package tech.beshu.ror.unit.api

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.matchers.ArgCapture.CaptureOne
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock.LdapUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.{MapsBasedMocksProvider, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.api.AuthMockApi
import tech.beshu.ror.api.AuthMockApi.AuthMockRequest.Type.{InvalidateAuthMock, UpdateAuthMock}
import tech.beshu.ror.api.AuthMockApi.{AuthMockRequest, Failure, Success}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class AuthMockApiTests extends AnyWordSpec with MockFactory {

  "Auth mock API" should {
    "have an update current auth mock method" which {
      "should succeed" when {
        "payload is parsable" in {
          val mutableMocksProvider = mock[MutableMocksProviderWithCachePerRequest]
          val mocksProvider = CaptureOne[MapsBasedMocksProvider]()
          val ttl = CaptureOne[Option[FiniteDuration]]()

          (mutableMocksProvider.update _)
            .expects(capture(mocksProvider), capture(ttl))
            .returning(())

          val api = new AuthMockApi(mutableMocksProvider)

          val response = wait(api.call(AuthMockRequest(
            aType = UpdateAuthMock,
            body =
              s"""
                 |{
                 |  "ldaps": {
                 |    "ldap2": {
                 |      "users": [
                 |        {
                 |          "name": "ldap_user_2",
                 |          "groups": ["group1", "group3"]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin,
            headers = Map.empty
          )))

          response should be(Success("Auth mock updated"))
          ttl.value should be (Some(30 minutes))
          mocksProvider.value should be (MapsBasedMocksProvider(
            ldapMocks = Map(
              LdapService.Name("ldap2") -> LdapServiceMock(
                Set(LdapUserMock(User.Id("ldap_user_2"), Set(Group("group1"), Group("group3"))))
              )
            )
          ))
        }
        "custom TTL header is set" in {
          val mutableMocksProvider = mock[MutableMocksProviderWithCachePerRequest]
          val mocksProvider = CaptureOne[MapsBasedMocksProvider]()
          val ttl = CaptureOne[Option[FiniteDuration]]()

          (mutableMocksProvider.update _)
            .expects(capture(mocksProvider), capture(ttl))
            .returning(())

          val api = new AuthMockApi(mutableMocksProvider)

          val response = wait(api.call(AuthMockRequest(
            aType = UpdateAuthMock,
            body =
              s"""
                 |{
                 |  "ldaps": {
                 |    "ldap2": {
                 |      "users": [
                 |        {
                 |          "name": "ldap_user_2",
                 |          "groups": ["group1", "group3"]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin,
            headers = Map("X-ROR-AUTH-MOCK-TTL" -> NonEmptyList.one("10 seconds"))
          )))

          response should be(Success("Auth mock updated"))
          ttl.value should be (Some(10 seconds))
        }
      }
      "should failed" when {
        "payload JSON is malformed" in {
          val mutableMocksProvider = mock[MutableMocksProviderWithCachePerRequest]
          val api = new AuthMockApi(mutableMocksProvider)

          val response = wait(api.call(AuthMockRequest(
            aType = UpdateAuthMock,
            body =
              s"""
                 |{
                 |  "ldaps: {}
                 |}
             """.stripMargin,
            headers = Map.empty
          )))

          response should be(Failure("Cannot parse JSON"))
        }
        "empty JSON is passed" in {
          val mutableMocksProvider = mock[MutableMocksProviderWithCachePerRequest]
          val api = new AuthMockApi(mutableMocksProvider)

          val response = wait(api.call(AuthMockRequest(
            aType = UpdateAuthMock,
            body = "{}",
            headers = Map.empty
          )))

          response should be(Failure("Invalid structure of sent JSON"))
        }
        "JSON with malformed ldap user objects" in {
          val mutableMocksProvider = mock[MutableMocksProviderWithCachePerRequest]
          val api = new AuthMockApi(mutableMocksProvider)

          val response = wait(api.call(AuthMockRequest(
            aType = UpdateAuthMock,
            body =
              s"""
                 |{
                 |  "ldaps": {
                 |    "ldap2": {
                 |      "users": [
                 |        {
                 |          "n": "ldap_user_2",
                 |          "g": ["group1", "group3"]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin,
            headers = Map.empty
          )))

          response should be(Failure("Invalid structure of sent JSON"))
        }
      }
    }
    "have an invalidate current auth mock method" which {
      "should succeed" in {
        val mocksProvider = mock[MutableMocksProviderWithCachePerRequest]
        (mocksProvider.invalidate _).expects().returning(())

        val api = new AuthMockApi(mocksProvider)

        val response = wait(api.call(AuthMockRequest(
          aType = InvalidateAuthMock,
          body = "",
          headers = Map.empty
        )))

        response should be(Success("Auth mock invalidated"))
      }
    }
  }

  private implicit val dummyRequestId: RequestId = RequestId("dummy")

  private def wait[T](action: Task[T]) = Await.result(action.runToFuture, 1 second)
}
