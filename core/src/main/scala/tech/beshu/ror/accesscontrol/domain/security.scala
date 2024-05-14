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
package tech.beshu.ror.accesscontrol.domain

import cats.Eq
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Claims
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.utils.ScalaOps.StringOps
import tech.beshu.ror.utils.json.JsonPath

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

final case class Credentials(user: User.Id, secret: PlainTextSecret)
object Credentials {
  implicit def eqCredentials(implicit eq: Eq[User.Id]): Eq[Credentials] =
    Eq.and(Eq.by(_.user), Eq.by(_.secret))
}

final case class BasicAuth private(credentials: Credentials) {
  def header: Header = new Header(
    Header.Name.authorization,
    NonEmptyString.unsafeFrom(s"Basic ${Base64.getEncoder.encodeToString(s"${credentials.user.value}:${credentials.secret.value}".getBytes(UTF_8))}")
  )
}
object BasicAuth extends Logging {
  def fromCredentials(credentials: Credentials) = {
    BasicAuth(credentials)
  }
  
  def fromHeader(header: Header): Option[BasicAuth] = {
    header.name match {
      case name if name === Header.Name.authorization => parse(header.value)
      case _ => None
    }
  }

  private def parse(headerValue: NonEmptyString) = {
    val authMethodName = "Basic "
    val rawValue = headerValue.value
    if (rawValue.startsWith(authMethodName) && rawValue.length > authMethodName.length) {
      val basicAuth = fromBase64(rawValue.substring(authMethodName.length))
      basicAuth match {
        case None =>
          logger.warn(s"Cannot decode value '$headerValue' to Basic Auth")
        case Some(_) =>
      }
      basicAuth
    } else {
      None
    }
  }

  private def fromBase64(base64Value: String) = {
    import tech.beshu.ror.utils.StringWiseSplitter._
    base64Value
      .decodeBase64
      .flatMap(_.toNonEmptyStringsTuple.toOption)
      .map { case (first, second) =>
        BasicAuth(Credentials(User.Id(first), PlainTextSecret(second)))
      }
  }
}

final case class ApiKey(value: NonEmptyString)
object ApiKey {
  implicit val eqApiKey: Eq[ApiKey] = Eq.fromUniversalEquals
}

final case class PlainTextSecret(value: NonEmptyString)
object PlainTextSecret {
  implicit val eqAuthKey: Eq[PlainTextSecret] = Eq.fromUniversalEquals
}

final case class Token(value: NonEmptyString)

final case class AuthorizationToken(value: NonEmptyString)

object Jwt {
  final case class ClaimName(name: JsonPath)

  final case class Token(value: NonEmptyString)

  final case class Payload(claims: Claims)
}