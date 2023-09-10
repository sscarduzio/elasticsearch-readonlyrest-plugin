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

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inside
import tech.beshu.ror.accesscontrol.domain.{BasicAuth, Credentials, PlainTextSecret, User}
import tech.beshu.ror.utils.TestsUtils._
import eu.timepit.refined.auto._

class BasicAuthTests extends AnyWordSpec with Inside {

  "BasicAuth class" should {
    "be able to be created from Authentication header" when {
      "its value is single, regular base64 encoded string" in {
        val basicAuth = BasicAuth.fromHeader(header("Authorization", "Basic a2liYW5hOmtpYmFuYQ=="))
        inside(basicAuth) {
          case Some(BasicAuth(Credentials(userId, secret))) =>
            userId should be (User.Id("kibana"))
            secret should be (PlainTextSecret("kibana"))
        }
      }
    }
    "not be able to be created" when {
      "other then Authorization header is passed" in {
        val basicAuth = BasicAuth.fromHeader(header("CustomAuthorization", "Basic a2liYW5hOmtpYmFuYQ=="))
        basicAuth should be (None)
      }
      "there is no Basic prefix in header value" in {
        val basicAuth = BasicAuth.fromHeader(header("Authorization", "a2liYW5hOmtpYmFuYQ=="))
        basicAuth should be (None)
      }
      "base64 value is malformed" in {
        val basicAuth = BasicAuth.fromHeader(header("Authorization", "Basic a2liYW5;;hO43tpYm"))
        basicAuth should be (None)
      }
      "credentials are malformed" in {
        val basicAuth = BasicAuth.fromHeader(header("Authorization", "Basic a2liYW5ha2liYW5h"))
        basicAuth should be (None)
      }
    }
  }
}
