package tech.beshu.ror.commons

import cats.Eq
import tech.beshu.ror.commons.aDomain.Header.Name

object aDomain {

  final case class Header(name: Name, value: String)
  object Header {
    final case class Name(value: String) extends AnyVal
    object Name {
      val xForwardedFor = Name("X-Forwarded-For")
      implicit val eqName: Eq[Name] = Eq.fromUniversalEquals
    }

    def apply(name: Name, value: String): Header = new Header(name, value)
    def apply(nameAndValue: (String, String)): Header = new Header(Name(nameAndValue._1), nameAndValue._2)

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

}
