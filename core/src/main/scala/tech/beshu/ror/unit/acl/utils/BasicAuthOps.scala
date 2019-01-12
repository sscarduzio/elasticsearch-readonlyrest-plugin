package tech.beshu.ror.unit.acl.utils

import java.nio.charset.StandardCharsets
import java.util.Base64

import tech.beshu.ror.commons.aDomain.AuthData
import tech.beshu.ror.utils.BasicAuthUtils.BasicAuth

import scala.language.implicitConversions
import scala.util.Try

class BasicAuthOps(val basicAuth: BasicAuth) extends AnyVal {

  def tryDecode: Try[AuthData] = {
    Try(new String(Base64.getDecoder.decode(basicAuth.getBase64Value), StandardCharsets.UTF_8).toString)
      .map(AuthData.apply)
  }
}

object BasicAuthOps {
  implicit def toBasicAuth(basicAuth: BasicAuth): BasicAuthOps = new BasicAuthOps(basicAuth)
}