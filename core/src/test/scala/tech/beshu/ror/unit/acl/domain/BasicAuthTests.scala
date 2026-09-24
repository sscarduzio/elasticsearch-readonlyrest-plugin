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
package tech.beshu.ror.unit.acl.domain

import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{BasicAuth, Credentials, PlainTextSecret, RequestId, User}
import tech.beshu.ror.utils.TestsUtils.*

import java.util.UUID

class BasicAuthTests extends AnyWordSpec with Inside {

  private implicit val requestId: RequestId = RequestId(UUID.randomUUID().toString)

  "BasicAuth class" should {
    "be able to be created from a header value" when {
      "the value is a single, regular base64 encoded string" in {
        val basicAuth = BasicAuth.parse(nes("Basic a2liYW5hOmtpYmFuYQ=="))
        inside(basicAuth) { case Some(BasicAuth(Credentials(userId, secret))) =>
          userId should be(User.Id("kibana"))
          secret should be(PlainTextSecret("kibana"))
        }
      }
    }
    "not be able to be created" when {
      "there is no Basic prefix in the value" in {
        BasicAuth.parse(nes("a2liYW5hOmtpYmFuYQ==")) should be(None)
      }
      "the base64 value is malformed" in {
        BasicAuth.parse(nes("Basic a2liYW5;;hO43tpYm")) should be(None)
      }
      "the credentials are malformed" in {
        BasicAuth.parse(nes("Basic a2liYW5ha2liYW5h")) should be(None)
      }
    }
  }

}
