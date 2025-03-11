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

import cats.data.NonEmptyList
import cats.{Eq, Show}
import com.comcast.ip4s.{Cidr, Hostname, IpAddress}
import eu.timepit.refined.auto.*
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError.*
import tech.beshu.ror.accesscontrol.header.ToHeaderValue
import tech.beshu.ror.constants
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.ScalaOps.*

import java.net.InetSocketAddress
import java.util.{Locale, UUID}
import scala.util.Try

final case class CorrelationId(value: NonEmptyString)
object CorrelationId {
  def random: CorrelationId = new CorrelationId(NonEmptyString.unsafeFrom(UUID.randomUUID().toString))

  implicit val show: Show[CorrelationId] = Show.show(_.value.value)
}

final case class Header(name: Header.Name, value: NonEmptyString)
object Header {
  final case class Name(value: NonEmptyString)
  object Name {
    val authorization = Name(nes("Authorization"))
    val xApiKeyHeaderName = Header.Name(nes("X-Api-Key"))
    val xForwardedFor = Name(nes("X-Forwarded-For"))
    val xForwardedUser = Name(nes("X-Forwarded-User"))
    val cookie = Name(nes("Cookie"))
    val setCookie = Name(nes("Set-Cookie"))
    val transientFields = Name(nes("_fields"))
    val userAgent = Name(nes("User-Agent"))

    val xUserOrigin = Name(nes("x-ror-origin"))
    val kibanaRequestPath = Name(nes("x-ror-kibana-request-path"))
    val currentGroup = Name(nes("x-ror-current-group"))
    val impersonateAs = Name(nes("x-ror-impersonating"))
    val correlationId = Name(nes("x-ror-correlation-id"))

    implicit val eqName: Eq[Name] = Eq.by(_.value.value.toLowerCase(Locale.US))
  }

  def apply(name: Name, value: NonEmptyString): Header = new Header(name, value)

  def apply[T](name: Name, value: T)
              (implicit ev: ToHeaderValue[T]): Header = new Header(name, ev.toRawValue(value))

  def apply(nameAndValue: (NonEmptyString, NonEmptyString)): Header = new Header(Name(nameAndValue._1), nameAndValue._2)

  def fromRawHeaders(headers: Map[String, List[String]]): Set[Header] = {
    val (authorizationHeaders, otherHeaders) =
      headers
        .map { case (name, values) => (name, values.toCovariantSet) }
        .flatMap { case (name, values) =>
          for {
            nonEmptyName <- NonEmptyString.unapply(name)
            nonEmptyValues <- NonEmptyList.fromList(values.toList.flatMap(NonEmptyString.unapply))
          } yield (Header.Name(nonEmptyName), nonEmptyValues)
        }
        .toSeq
        .partition { case (name, _) => name === Header.Name.authorization }
    val headersFromAuthorizationHeaderValues = authorizationHeaders
      .flatMap { case (_, values) =>
        val headersFromAuthorizationHeaderValues = values
          .map(fromAuthorizationValue)
          .toList
          .map(_.map(_.toList))
          .sequence
          .map(_.flatten)
        headersFromAuthorizationHeaderValues match {
          case Left(error) => throw new IllegalArgumentException(error.show)
          case Right(v) => v
        }
      }
      .toCovariantSet
    val restOfHeaders = otherHeaders
      .flatMap { case (name, values) => values.map(new Header(name, _)).toList }
      .toCovariantSet
    val restOfHeaderNames = restOfHeaders.map(_.name)
    restOfHeaders ++ headersFromAuthorizationHeaderValues.filter { header => !restOfHeaderNames.contains(header.name) }
  }

  def fromAuthorizationValue(value: NonEmptyString): Either[AuthorizationValueError, NonEmptyList[Header]] = {
    value.value.splitBy("ror_metadata=") match {
      case (_, None) =>
        Right(NonEmptyList.one(new Header(Name.authorization, value)))
      case (basicAuthStr, Some(rorMetadata)) =>
        for {
          authorizationHeader <- createHeaderFromAuthorizationString(basicAuthStr)
          headersStr <- parseRorMetadataString(rorMetadata)
          headers <- headersStr.map(headerFrom).traverse(identity)
        } yield NonEmptyList.of(authorizationHeader, headers: _*)
    }
  }

  private def createHeaderFromAuthorizationString(authStr: String) = {
    val trimmed = authStr.trim
    val sanitized = if (trimmed.endsWith(",")) trimmed.substring(0, trimmed.length - 1) else trimmed
    NonEmptyString
      .from(sanitized)
      .map(new Header(Name.authorization, _))
      .left.map(_ => EmptyAuthorizationValue)
  }

  private def parseRorMetadataString(rorMetadataString: String) = {
    rorMetadataString.decodeBase64 match {
      case Some(value) =>
        Try(ujson.read(value).obj("headers").arr.toList.map(_.str))
          .toEither.left.map(_ => RorMetadataInvalidFormat(rorMetadataString, "Parsing JSON failed"))
      case None =>
        Left(RorMetadataInvalidFormat(rorMetadataString, "Decoding Base64 failed"))
    }
  }

  private def headerFrom(value: String) = {
    import tech.beshu.ror.utils.StringWiseSplitter.*
    value
      .toNonEmptyStringsTuple
      .bimap(
        { case Error.CannotSplitUsingColon | Error.TupleMemberCannotBeEmpty => InvalidHeaderFormat(value) },
        { case (nonEmptyName, nonEmptyValue) => new Header(Name(nonEmptyName), nonEmptyValue) }
      )
  }

  sealed trait AuthorizationValueError
  object AuthorizationValueError {
    case object EmptyAuthorizationValue extends AuthorizationValueError
    final case class InvalidHeaderFormat(value: String) extends AuthorizationValueError
    final case class RorMetadataInvalidFormat(value: String, message: String) extends AuthorizationValueError
  }

  implicit val eqHeader: Eq[Header] = Eq.by[Header, (String, String)](header => (header.name.value, header.value.value))
}

sealed trait Address
object Address {
  final case class Ip(value: Cidr[IpAddress]) extends Address {
    def contains(ip: Ip): Boolean = value.contains(ip.value.address)
  }
  final case class Name(value: Hostname) extends Address

  def from(value: String): Option[Address] = {
    parseCidr(value) orElse
      parseIpAddress(value) orElse
      parseHostname(value)
  }
  
  def from(inetSocketAddress: InetSocketAddress): Option[Address] = {
    for {
      inetAddress <- Option(inetSocketAddress.getAddress)
      hostAddress <- Option(inetAddress.getHostAddress)
      address <- Address.from(hostAddress)
    } yield address
  }

  private def parseCidr(value: String) =
    Cidr.fromString(value).map(Address.Ip.apply)

  private def parseHostname(value: String) =
    Hostname.fromString(value).map(Address.Name.apply)

  private def parseIpAddress(value: String) =
    (cutOffZoneIndex _ andThen IpAddress.fromString andThen (_.map(createAddressIp))) (value)

  private def createAddressIp(ip: IpAddress) =
    Address.Ip(Cidr(ip, 32))

  private val ipv6WithLiteralScope = raw"""(?i)^(fe80:[a-z0-9:]+)%.*$$""".r

  private def cutOffZoneIndex(value: String): String = {
    //https://en.wikipedia.org/wiki/IPv6_address#Scoped_literal_IPv6_addresses
    value match {
      case ipv6WithLiteralScope(ipv6) => ipv6
      case noLiteralIp => noLiteralIp
    }
  }
}

final case class UriPath private(value: NonEmptyString) {
  def isAuditEventPath: Boolean =
    this != UriPath.slashPath && UriPath.auditEventPath.value.value.startsWith(value.value)

  def isCurrentUserMetadataPath: Boolean =
    this != UriPath.slashPath && UriPath.currentUserMetadataPath.value.value.startsWith(value.value)

  def isCatTemplatePath: Boolean = value.value.startsWith("/_cat/templates")

  def isTemplatePath: Boolean = value.value.startsWith("/_template")

  def isCatIndicesPath: Boolean = value.value.startsWith("/_cat/indices")

  def isSqlQueryPath: Boolean = value.value.startsWith("/_sql")
  
  def isEsqlQueryPath: Boolean = value.value.startsWith("/_query")
  
  def isAliasesPath: Boolean =
    value.value.startsWith("/_cat/aliases") ||
      value.value.startsWith("/_alias") ||
      "^/(\\w|\\*)*/_alias(|/)$".r.findFirstMatchIn(value.value).isDefined ||
      "^/(\\w|\\*)*/_alias/(\\w|\\*)*(|/)$".r.findFirstMatchIn(value.value).isDefined
}
object UriPath {
  val currentUserMetadataPath = UriPath(NonEmptyString.unsafeFrom(constants.CURRENT_USER_METADATA_PATH))
  val auditEventPath = UriPath(NonEmptyString.unsafeFrom(constants.AUDIT_EVENT_COLLECTOR_PATH))
  val slashPath = UriPath(nes("/"))

  implicit val eqUriPath: Eq[UriPath] = Eq.fromUniversalEquals

  def from(value: String): Option[UriPath] = {
    NonEmptyString
      .from(value).toOption
      .map(UriPath.from)
  }

  def from(value: NonEmptyString): UriPath = {
    if (value.startsWith("/")) new UriPath(value)
    else new UriPath(NonEmptyString.unsafeFrom(s"/$value"))
  }

  object CatTemplatePath {
    def unapply(uriPath: UriPath): Option[UriPath] = {
      if (uriPath.isCatTemplatePath) Some(uriPath)
      else None
    }
  }

  object CatIndicesPath {
    def unapply(uriPath: UriPath): Option[UriPath] = {
      if (uriPath.isCatIndicesPath) Some(uriPath)
      else None
    }
  }

  object TemplatePath {
    def unapply(uriPath: UriPath): Option[UriPath] = {
      if (uriPath.isTemplatePath) Some(uriPath)
      else None
    }
  }

  object AliasesPath {
    def unapply(uriPath: UriPath): Option[UriPath] = {
      if (uriPath.isAliasesPath) Some(uriPath)
      else None
    }
  }

  object CurrentUserMetadataPath {
    def unapply(uriPath: UriPath): Option[UriPath] = {
      if (uriPath.isCurrentUserMetadataPath) Some(uriPath)
      else None
    }
  }
}

final case class AuthorizationTokenDef(headerName: Header.Name,
                                       prefix: String)

final case class UserOrigin(value: NonEmptyString)