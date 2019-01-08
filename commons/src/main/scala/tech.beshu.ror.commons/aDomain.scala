package tech.beshu.ror.commons

import cats.{Eq, Show}
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.header.ToHeaderValue

object aDomain {

  final case class Header(name: Name, value: String)
  object Header {
    final case class Name(value: String)
    object Name {
      val xForwardedFor = Name("X-Forwarded-For")
      val xForwardedUser = Name("X-Forwarded-User")
      val kibanaHiddenApps = Name(Constants.HEADER_KIBANA_HIDDEN_APPS)
      val cookie = Name("Cookie")
      val setCookie = Name("Set-Cookie")
      val transientFields = Name(Constants.FIELDS_TRANSIENT)
      val currentGroup = Name(Constants.HEADER_GROUP_CURRENT)
      val userAgent = Name("User-Agent")
      val authorization = Name("Authorization")
      val rorUser = Name(Constants.HEADER_USER_ROR)
      val kibanaIndex = Name(Constants.HEADER_KIBANA_INDEX)

      implicit val eqName: Eq[Name] = Eq.fromUniversalEquals
    }

    def apply(name: Name, value: String): Header = new Header(name, value)
    def apply(nameAndValue: (String, String)): Header = new Header(Name(nameAndValue._1), nameAndValue._2)
    def apply[T](name: Name, value: T)
                (implicit ev: ToHeaderValue[T]): Header = new Header(name, ev.toRawValue(value))

    implicit val eqHeader: Eq[Header] = Eq.fromUniversalEquals
  }

  final case class Address(value: String) extends AnyVal
  object Address {
    val unknown = Address("unknown")
    implicit val eqAddress: Eq[Address] = Eq.fromUniversalEquals
  }

  final case class Action(value: String) extends AnyVal
  object Action {
    implicit val eqAction: Eq[Action] = Eq.fromUniversalEquals
  }

  final case class IndexName(value: String) extends AnyVal
  object IndexName {
    implicit val eqIndexName: Eq[IndexName] = Eq.fromUniversalEquals
  }

  final case class ApiKey(value: String) extends AnyVal
  object ApiKey {
    implicit val eqApiKey: Eq[ApiKey] = Eq.fromUniversalEquals
  }

  final case class AuthData(value: String) extends AnyVal
  object AuthData {
    implicit val eqAuthKey: Eq[AuthData] = Eq.fromUniversalEquals
    val empty: AuthData = AuthData("")
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

    implicit val show: Show[DocumentField] = Show.show {
      case f: ADocumentField => f.value
      case f: NegatedDocumentField => s"~${f.value}"
    }
  }

  final case class Type(value: String) extends AnyVal
}
