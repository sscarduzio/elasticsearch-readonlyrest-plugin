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
package tech.beshu.ror.unit.acl.request

import org.apache.logging.log4j.Level
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.request.{RequestContext, RequestHeaders}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.LogEventsCapture.captureLogEvents
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList

class RequestHeadersTests extends AnyWordSpec with Matchers {

  "basicAuth" should {
    "read the credentials from one Authorization header" in {
      request(basicAuthHeader("user:pass")).basicAuth.map(_.credentials) should be(Some(userPass))
    }
    "read the credentials when the same Authorization value comes twice" in {
      request(
        basicAuthHeader("user:pass"),
        headerFrom("AUTHORIZATION" -> basicAuthHeader("user:pass").value.value)
      ).basicAuth.map(_.credentials) should be(Some(userPass))
    }
    "read no credentials when Authorization holds two different Basic values" in {
      request(basicAuthHeader("user:pass"), basicAuthHeader("other:pass")).basicAuth should be(None)
    }
    "read no credentials when Authorization holds a Basic value and a Bearer value" in {
      request(basicAuthHeader("user:pass"), headerFrom("Authorization" -> "Bearer x")).basicAuth should be(None)
    }
  }

  "rawAuthHeader" should {
    "read the value of one Authorization header" in {
      request(headerFrom("Authorization" -> "Bearer x")).rawAuthHeader.map(_.value.value) should be(Some("Bearer x"))
    }
    "read no value when Authorization holds two different values" in {
      request(
        headerFrom("Authorization" -> "Bearer x"),
        headerFrom("authorization" -> "Bearer y")
      ).rawAuthHeader should be(None)
    }
  }

  "currentGroupId" should {
    "read the group from one header" in {
      request(headerFrom("x-ror-current-group" -> "g1")).currentGroupId should be(Some(GroupId(nes("g1"))))
    }
    "read the group when the same value comes twice" in {
      request(
        headerFrom("x-ror-current-group" -> "g1"),
        headerFrom("X-ROR-CURRENT-GROUP" -> "g1")
      ).currentGroupId should be(Some(GroupId(nes("g1"))))
    }
    "read no group when the header holds two different values" in {
      request(
        headerFrom("x-ror-current-group" -> "g1"),
        headerFrom("X-ROR-CURRENT-GROUP" -> "g2")
      ).currentGroupId should be(None)
    }
  }

  "kibanaRequestPath" should {
    "read the path from one header" in {
      request(headerFrom("x-ror-kibana-request-path" -> "/app/home")).kibanaRequestPath should be(
        Some(nes("/app/home"))
      )
    }
    "read no path when the header holds two different values" in {
      request(
        headerFrom("x-ror-kibana-request-path" -> "/app/home"),
        headerFrom("x-ror-kibana-request-path" -> "/app/discover")
      ).kibanaRequestPath should be(None)
    }
  }

  "rorKbnLicenseType" should {
    "read the license type from one header" in {
      request(headerFrom("x-ror-kbn-license-type" -> "pro")).rorKbnLicenseType should be(Some(RorKbnLicenseType.Pro))
    }
    "read no license type when the header holds two different values" in {
      request(
        headerFrom("x-ror-kbn-license-type" -> "pro"),
        headerFrom("x-ror-kbn-license-type" -> "free")
      ).rorKbnLicenseType should be(None)
    }
  }

  "impersonateAs" should {
    "read the user from one header" in {
      request(headerFrom("x-ror-impersonating" -> "user1")).impersonateAs should be(Some(User.Id(nes("user1"))))
    }
    "read no user when the header holds two different values" in {
      request(
        headerFrom("x-ror-impersonating" -> "user1"),
        headerFrom("x-ror-impersonating" -> "user2")
      ).impersonateAs should be(None)
    }
  }

  "correlationId" should {
    "read the correlation ID from one header" in {
      request(headerFrom("x-ror-correlation-id" -> "id1")).correlationId.value should be(
        CorrelationId(nes("id1"))
      )
    }
    "generate a random correlation ID when the header holds two different values" in {
      val correlationId = request(
        headerFrom("x-ror-correlation-id" -> "id1"),
        headerFrom("x-ror-correlation-id" -> "id2")
      ).correlationId.value
      List(CorrelationId(nes("id1")), CorrelationId(nes("id2"))) should not contain correlationId
    }
  }

  "an ambiguous header" should {
    "log one warning, however many times the readers read it" in {
      val headers = request(
        headerFrom("x-ror-current-group" -> "g1"),
        headerFrom("x-ror-current-group" -> "g2")
      )
      val events = captureLogEvents(Header.getClass.getName) {
        (1 to 32).foreach(_ => headers.currentGroupId should be(None))
      }
      events.map(_.level) should be(List(Level.WARN))
    }
    "log one warning for each REST request, however many request contexts read it" in {
      val requestContext = MockRequestContext.indices.withHeaders(
        headerFrom("x-ror-current-group" -> "g1"),
        headerFrom("x-ror-current-group" -> "g2")
      )
      val events = captureLogEvents(Header.getClass.getName) {
        (1 to 32).foreach { i =>
          requestContext.copy(id = RequestContext.Id.fromString(s"id-$i")).headers.currentGroupId should be(None)
        }
      }
      events.map(_.level) should be(List(Level.WARN))
    }
  }

  private def request(header: Header, headers: Header*) =
    new RequestHeaders(UniqueList.from(header +: headers))

  private val userPass = Credentials(User.Id(nes("user")), PlainTextSecret(nes("pass")))

}
