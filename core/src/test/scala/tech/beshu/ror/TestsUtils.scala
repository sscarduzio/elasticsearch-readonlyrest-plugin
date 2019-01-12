package tech.beshu.ror

import java.nio.file.Path
import java.time.Duration
import java.util.Base64

import io.circe.Json
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.aDomain.Header.Name

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object TestsUtils {

  def basicAuthHeader(value: String): Header =
    Header(Name("Authorization"), "Basic " + Base64.getEncoder.encodeToString(value.getBytes))

  implicit def scalaFiniteDuration2JavaDuration(duration: FiniteDuration): Duration = Duration.ofMillis(duration.toMillis)

  def jsonFrom(value: String): Json = {
    io.circe.parser.parse(value).right.getOrElse(throw new IllegalArgumentException(s"Cannot parse string $value to JSON"))
  }
}
