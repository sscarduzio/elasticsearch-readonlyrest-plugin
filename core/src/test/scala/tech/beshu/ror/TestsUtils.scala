package tech.beshu.ror

import java.util.Base64

import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.aDomain.Header.Name

object TestsUtils {

  def basicAuthHeader(value: String): Header =
    Header(Name("Authorization"), "Basic " + Base64.getEncoder.encodeToString(value.getBytes))
}
