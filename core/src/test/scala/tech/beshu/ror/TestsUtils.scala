package tech.beshu.ror

import java.util.Base64

import tech.beshu.ror.commons.aDomain.Header

object TestsUtils {

  def basicAuthHeader(value: String): Header =
    Header("Authorization", "Basic " + Base64.getEncoder.encodeToString(value.getBytes))
}
