package tech.beshu.ror.unit.acl.domain

import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.domain.{BasicAuth, Credentials, PlainTextSecret, User}
import tech.beshu.ror.utils.TestsUtils._

class BasicAuthTest extends WordSpec with Inside {

  "BasicAuth class" should {
    "be able to be created from Authentication header" when {
      "its value is single, regular base64 encoded string" in {
        val basicAuth = BasicAuth.fromHeader(header("Authorization", "Basic a2liYW5hOmtpYmFuYQ=="))
        inside(basicAuth) {
          case Some(BasicAuth(Credentials(userId, secret))) =>
            userId should be (User.Id("kibana".nonempty))
            secret should be (PlainTextSecret("kibana".nonempty))
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
