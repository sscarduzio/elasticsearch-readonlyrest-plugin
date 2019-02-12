package tech.beshu.ror.acl

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

import cats.Eq
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.header.ToHeaderValue
import tech.beshu.ror.Constants

import scala.util.Try

object aDomain {

  final case class LoggedUser(id: User.Id)
  object LoggedUser {
    implicit val eqLoggedUser: Eq[LoggedUser] = Eq.fromUniversalEquals
  }

  object User {
    final case class Id(value: String)
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
      val kibanaHiddenApps = Name(NonEmptyString.unsafeFrom(Constants.HEADER_KIBANA_HIDDEN_APPS))
      val cookie = Name(NonEmptyString.unsafeFrom("Cookie"))
      val setCookie = Name(NonEmptyString.unsafeFrom("Set-Cookie"))
      val transientFields = Name(NonEmptyString.unsafeFrom(Constants.FIELDS_TRANSIENT))
      val currentGroup = Name(NonEmptyString.unsafeFrom(Constants.HEADER_GROUP_CURRENT))
      val availableGroups = Name(NonEmptyString.unsafeFrom(Constants.HEADER_GROUPS_AVAILABLE))
      val userAgent = Name(NonEmptyString.unsafeFrom("User-Agent"))
      val authorization = Name(NonEmptyString.unsafeFrom("Authorization"))
      val rorUser = Name(NonEmptyString.unsafeFrom(Constants.HEADER_USER_ROR))
      val kibanaIndex = Name(NonEmptyString.unsafeFrom(Constants.HEADER_KIBANA_INDEX))
      val kibanaAccess = Name(NonEmptyString.unsafeFrom(Constants.HEADER_KIBANA_ACCESS))
      val transientFilter = Name(NonEmptyString.unsafeFrom(Constants.FILTER_TRANSIENT))

      implicit val eqName: Eq[Name] = Eq.by(_.value.value.toLowerCase)
    }

    def apply(name: Name, value: NonEmptyString): Header = new Header(name, value)
    def apply[T](name: Name, value: T)
                (implicit ev: ToHeaderValue[T]): Header = new Header(name, NonEmptyString.unsafeFrom(ev.toRawValue(value))) // todo: remove unsafe in future
    def apply(nameAndValue: (NonEmptyString, NonEmptyString)): Header = new Header(Name(nameAndValue._1), nameAndValue._2)

    implicit val eqHeader: Eq[Header] = Eq.fromUniversalEquals
  }

  final case class BasicAuth private(user: User.Id, secret: Secret) {
    def header: Header = Header(
      Header.Name.authorization,
      NonEmptyString.unsafeFrom(s"Basic ${Base64.getEncoder.encodeToString(s"${user.value}:${secret.value}".getBytes(UTF_8))}")
    )
    def colonSeparatedString: String = s"${user.value}:${secret.value}"
  }
  object BasicAuth extends Logging {
    def fromHeader(header: Header): Option[BasicAuth] = {
      header.name match {
        case Header.Name.authorization => parseToBasicAuth(header.value)
        case _ => None
      }
    }

    private def parseToBasicAuth(headerValue: NonEmptyString) = {
      val authMethodName = "Basic "
      val rawValue = headerValue.value
      if(rawValue.startsWith(authMethodName) && rawValue.length > authMethodName.length) {
        val basicAuth = base64ToBasicAuth(rawValue.substring(authMethodName.length))
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

    private def base64ToBasicAuth(base64Value: String) = {
      Try(new String(Base64.getDecoder.decode(base64Value), UTF_8))
        .map { decoded =>
          decoded.indexOf(":") match {
            case -1 => None
            case index if index == decoded.length -1 => None
            case index => Some(BasicAuth(
              User.Id(decoded.substring(0, index)),
              Secret(decoded.substring(index + 1))
            ))
          }
        }
        .getOrElse(None)
    }
  }

  final case class Address(value: String) extends AnyVal
  object Address {
    val unknown = Address("unknown")
    implicit val eqAddress: Eq[Address] = Eq.fromUniversalEquals
  }

  final case class Action(value: String) extends AnyVal {
    def hasPrefix(prefix: String): Boolean = value.startsWith(prefix)

    def isSnapshotAction: Boolean = value.contains("/snapshot/")
    def isRepositoryAction: Boolean = value.contains("/repository/")
  }
  object Action {
    val searchAction = Action("indices:data/read/search")
    val mSearchAction = Action("indices:data/read/msearch")

    implicit val eqAction: Eq[Action] = Eq.fromUniversalEquals
  }

  final case class IndexName(value: String) {
    def isClusterIndex: Boolean = value.contains(":")
    def hasPrefix(prefix: String): Boolean = value.startsWith(prefix)
    def hasWildcard: Boolean = value.contains("*")
  }
  object IndexName {

    val wildcard = IndexName("*")
    val all = IndexName("_all")
    val devNullKibana = IndexName(".kibana-devnull")
    val kibana = IndexName(".kibana")
    val readonlyrest: IndexName = IndexName(".readonlyrest")

    implicit val eqIndexName: Eq[IndexName] = Eq.fromUniversalEquals
  }

  final case class ApiKey(value: NonEmptyString)
  object ApiKey {
    implicit val eqApiKey: Eq[ApiKey] = Eq.fromUniversalEquals
  }

  final case class Secret(value: String) extends AnyVal
  object Secret {
    implicit val eqAuthKey: Eq[Secret] = Eq.fromUniversalEquals
    val empty: Secret = Secret("")
  }

  final case class KibanaApp(value: String) extends AnyVal
  object KibanaApp {
    implicit val eqKibanaApps: Eq[KibanaApp] = Eq.fromUniversalEquals
  }

  sealed abstract class DocumentField(val value: String)
  object DocumentField {
    final case class ADocumentField(override val value: String) extends DocumentField(value)
    final case class NegatedDocumentField(override val value: String) extends DocumentField(value)

    val all = ADocumentField("_all")
    val notAll = NegatedDocumentField("_all")
  }

  final case class Type(value: String) extends AnyVal

  final case class Filter(value: String) extends AnyVal

  sealed trait KibanaAccess
  object KibanaAccess {
    case object RO extends KibanaAccess
    case object RW extends KibanaAccess
    case object ROStrict extends KibanaAccess
    case object Admin extends KibanaAccess

    implicit val eqKibanaAccess: Eq[KibanaAccess] = Eq.fromUniversalEquals
  }

  final case class UriPath(value: String) {
    def isRestMetadataPath: Boolean = {
      value.startsWith(UriPath.restMetadataPath.value)
    }
  }
  object UriPath {
    val restMetadataPath = UriPath(Constants.REST_METADATA_PATH)
    implicit val eqUriPath: Eq[UriPath] = Eq.fromUniversalEquals
  }


  final case class ClaimName(value: NonEmptyString)

  final case class JwtToken(value: NonEmptyString)
}
