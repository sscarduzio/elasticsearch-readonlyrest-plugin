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
package tech.beshu.ror.accesscontrol

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, Locale}

import cats.Eq
import cats.data.NonEmptyList
import cats.implicits._
import com.comcast.ip4s.interop.cats.HostnameResolver
import com.comcast.ip4s.{Cidr, Hostname, IpAddress}
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Claims
import monix.eval.Task
import org.apache.commons.lang.RandomStringUtils.randomAlphanumeric
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.header.ToHeaderValue
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

import scala.util.Try

object domain {

  sealed trait LoggedUser {
    def id: User.Id
  }
  object LoggedUser {
    final case class DirectlyLoggedUser(id: User.Id) extends LoggedUser
    final case class ImpersonatedUser(id: User.Id, impersonatedBy: User.Id) extends LoggedUser

    implicit val eqLoggedUser: Eq[DirectlyLoggedUser] = Eq.fromUniversalEquals
  }

  object User {
    final case class Id(value: NonEmptyString)
    object Id {
      implicit val eqId: Eq[Id] = Eq.fromUniversalEquals
    }
  }

  final case class Group(value: NonEmptyString)

  final case class Header(name: Header.Name, value: NonEmptyString)
  object Header {
    final case class Name(value: NonEmptyString)
    object Name {
      val xApiKeyHeaderName = Header.Name(NonEmptyString.unsafeFrom("X-Api-Key"))
      val xForwardedFor = Name(NonEmptyString.unsafeFrom("X-Forwarded-For"))
      val xForwardedUser = Name(NonEmptyString.unsafeFrom("X-Forwarded-User"))
      val xUserOrigin = Name(NonEmptyString.unsafeFrom(Constants.HEADER_USER_ORIGIN))
      val kibanaHiddenApps = Name(NonEmptyString.unsafeFrom(Constants.HEADER_KIBANA_HIDDEN_APPS))
      val cookie = Name(NonEmptyString.unsafeFrom("Cookie"))
      val setCookie = Name(NonEmptyString.unsafeFrom("Set-Cookie"))
      val transientFields = Name(NonEmptyString.unsafeFrom(Constants.FIELDS_TRANSIENT))
      val currentGroup = Name(NonEmptyString.unsafeFrom(Constants.HEADER_GROUP_CURRENT))
      val availableGroups = Name(NonEmptyString.unsafeFrom(Constants.HEADER_GROUPS_AVAILABLE))
      val userAgent = Name(NonEmptyString.unsafeFrom("User-Agent"))
      val authorization = Name(NonEmptyString.unsafeFrom("Authorization"))
      val rorUser = Name(NonEmptyString.unsafeFrom(Constants.HEADER_USER_ROR))
      val kibanaAccess = Name(NonEmptyString.unsafeFrom(Constants.HEADER_KIBANA_ACCESS))
      val transientFilter = Name(NonEmptyString.unsafeFrom(Constants.FILTER_TRANSIENT))
      val impersonateAs = Name(NonEmptyString.unsafeFrom("impersonate_as"))

      implicit val eqName: Eq[Name] = Eq.by(_.value.value.toLowerCase(Locale.US))
    }

    def apply(name: Name, value: NonEmptyString): Header = new Header(name, value)
    def apply[T](name: Name, value: T)
                (implicit ev: ToHeaderValue[T]): Header = new Header(name, ev.toRawValue(value))
    def apply(nameAndValue: (NonEmptyString, NonEmptyString)): Header = new Header(Name(nameAndValue._1), nameAndValue._2)

    implicit val eqHeader: Eq[Header] = Eq.fromUniversalEquals
  }

  final case class Credentials(user: User.Id, secret: PlainTextSecret)
  final case class BasicAuth private(credentials: Credentials) {
    def header: Header = Header(
      Header.Name.authorization,
      NonEmptyString.unsafeFrom(s"Basic ${Base64.getEncoder.encodeToString(s"${credentials.user.value}:${credentials.secret.value}".getBytes(UTF_8))}")
    )
  }
  object BasicAuth extends Logging {
    def fromHeader(header: Header): Option[BasicAuth] = {
      header.name match {
        case name if name === Header.Name.authorization => parse(header.value)
        case _ => None
      }
    }

    private def parse(headerValue: NonEmptyString) = {
      val authMethodName = "Basic "
      val rawValue = headerValue.value
      if(rawValue.startsWith(authMethodName) && rawValue.length > authMethodName.length) {
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
      Try(new String(Base64.getDecoder.decode(base64Value), UTF_8))
        .toOption
        .flatMap(_.toNonEmptyStringsTuple.toOption)
        .map { case (first, second) =>
          BasicAuth(Credentials(User.Id(first), PlainTextSecret(second)))
        }
    }
  }

  sealed trait Address
  object Address {
    final case class Ip(value: Cidr[IpAddress]) extends Address {
      def contains(ip: Ip): Boolean = value.contains(ip.value.address)
    }
    final case class Name(value: Hostname) extends Address

    def from(value: String): Option[Address] = {
      Cidr.fromString(value).map(Ip.apply) orElse
        IpAddress(value).map(ip => Ip(Cidr(ip, 32))) orElse
        Hostname(value).map(Name.apply)
    }

    // fixme: (improvements) blocking resolving (shift to another EC)
    def resolve(hostname: Name): Task[Option[NonEmptyList[Ip]]] = {
      HostnameResolver.resolveAll[Task](hostname.value).map(_.map(_.map(ip => Ip(Cidr(ip, 32)))))
    }
  }

  final case class Action(value: String) extends AnyVal {
    def hasPrefix(prefix: String): Boolean = value.startsWith(prefix)

    def isSnapshot: Boolean = value.contains("/snapshot/")
    def isRepository: Boolean = value.contains("/repository/")
    def isTemplate: Boolean = value.contains("/template/")
  }
  object Action {
    val searchAction = Action("indices:data/read/search")
    val mSearchAction = Action("indices:data/read/msearch")

    implicit val eqAction: Eq[Action] = Eq.fromUniversalEquals
  }

  final case class IndexName(value: NonEmptyString) {
    def isClusterIndex: Boolean = value.value.contains(":")
    def hasPrefix(prefix: String): Boolean = value.value.startsWith(prefix)
    def hasWildcard: Boolean = value.value.contains("*")
  }
  object IndexName {
    val wildcard: IndexName = fromUnsafeString("*")
    val all: IndexName = fromUnsafeString("_all")
    val devNullKibana: IndexName = fromUnsafeString(".kibana-devnull")
    val kibana: IndexName = fromUnsafeString(".kibana")
    val readonlyrest: IndexName = fromUnsafeString(".readonlyrest")

    implicit val eqIndexName: Eq[IndexName] = Eq.fromUniversalEquals

    def fromString(value: String): Option[IndexName] = NonEmptyString.from(value).map(from).toOption

    def fromUnsafeString(value: String): IndexName = from(NonEmptyString.unsafeFrom(value))

    def randomNonexistentIndex(prefix: String = ""): IndexName = from {
      NonEmptyString.unsafeFrom {
        val nonexistentIndex = s"${NonEmptyString.unapply(prefix).map(i => s"${i}_").getOrElse("")}ROR_${randomAlphanumeric(10)}"
        if(prefix.contains("*")) s"$nonexistentIndex*"
        else nonexistentIndex
      }
    }

    private def from(name: NonEmptyString) = {
      IndexName(name) match {
        case index if index == all => wildcard
        case index => index
      }
    }
  }

  final case class IndexWithAliases(index: IndexName, aliases: Set[IndexName]) {
    def all: Set[IndexName] = aliases + index
  }

  final case class ApiKey(value: NonEmptyString)
  object ApiKey {
    implicit val eqApiKey: Eq[ApiKey] = Eq.fromUniversalEquals
  }

  final case class PlainTextSecret(value: NonEmptyString)
  object PlainTextSecret {
    implicit val eqAuthKey: Eq[PlainTextSecret] = Eq.fromUniversalEquals
  }

  final case class KibanaApp(value: NonEmptyString)
  object KibanaApp {
    implicit val eqKibanaApps: Eq[KibanaApp] = Eq.fromUniversalEquals
  }

  final case class UserOrigin(value: NonEmptyString)

  sealed abstract class DocumentField(val value: NonEmptyString)
  object DocumentField {
    final case class ADocumentField(override val value: NonEmptyString) extends DocumentField(value)
    final case class NegatedDocumentField(override val value: NonEmptyString) extends DocumentField(value)
  }

  final case class Type(value: String) extends AnyVal

  final case class Filter(value: NonEmptyString)

  sealed trait KibanaAccess
  object KibanaAccess {
    case object RO extends KibanaAccess
    case object RW extends KibanaAccess
    case object ROStrict extends KibanaAccess
    case object Admin extends KibanaAccess

    implicit val eqKibanaAccess: Eq[KibanaAccess] = Eq.fromUniversalEquals
  }

  final case class UriPath(value: String) {
    def isCurrentUserMetadataPath: Boolean = value.startsWith(UriPath.currentUserMetadataPath.value)
    def isCatTemplatePath: Boolean = value.startsWith("/_cat/templates")
    def isTemplatePath: Boolean = value.startsWith("/_template")
    def isAliasesPath: Boolean =
      value.startsWith("/_cat/aliases") ||
        value.startsWith("/_alias") ||
        "^/(\\w|\\*)*/_alias(|/)$".r.findFirstMatchIn(value).isDefined ||
        "^/(\\w|\\*)*/_alias/(\\w|\\*)*(|/)$".r.findFirstMatchIn(value).isDefined
    def isCatIndicesPath: Boolean = value.startsWith("/_cat/indices")

  }
  object UriPath {
    val currentUserMetadataPath = UriPath(Constants.CURRENT_USER_METADATA_PATH)
    implicit val eqUriPath: Eq[UriPath] = Eq.fromUniversalEquals

    object CatTemplatePath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isCatTemplatePath) Some(uriPath)
        else None
      }
    }

    object CatIndicesPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isCatIndicesPath) Some(uriPath)
        else None
      }
    }

    object TemplatePath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isTemplatePath) Some(uriPath)
        else None
      }
    }

    object AliasesPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isAliasesPath) Some(uriPath)
        else None
      }
    }

    object CurrentUserMetadataPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isCurrentUserMetadataPath) Some(uriPath)
        else None
      }
    }
  }


  final case class ClaimName(name: JsonPath) {

    override def equals(other: Any): Boolean = {
      other match {
        case that: ClaimName => that.name.getPath.equals(this.name.getPath)
        case _ => false
      }
    }

    override def hashCode: Int = name.getPath.hashCode
  }

  final case class JwtToken(value: NonEmptyString)

  final case class JwtTokenPayload(claims: Claims)

  final case class AuthorizationTokenDef(headerName: Header.Name, prefix: String)

  final case class AuthorizationToken(value: NonEmptyString)

}
