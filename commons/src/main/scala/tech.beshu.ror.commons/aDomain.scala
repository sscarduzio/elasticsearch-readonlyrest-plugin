package tech.beshu.ror.commons

object aDomain {

  final case class Header private(name: String, value: String)
  object Header {
    def apply(name: String, value: String): Header = create(name, value)
    def create(name: String, value: String): Header = new Header(name.toLowerCase, value)
    def create(nameAndValue: (String, String)): Header = create(nameAndValue._1, nameAndValue._2)
  }

  final case class UnresolvedAddress(value: String) extends AnyVal
}
