package tech.beshu.ror.commons

object aDomain {

  final case class Header(name: String, value: String)
  object Header {
    def apply(name: String, value: String): Header = new Header(name, value)
    def apply(nameAndValue: (String, String)): Header = new Header(nameAndValue._1, nameAndValue._2)
  }
  final case class Address(value: String) extends AnyVal
  object Address {
    val unknown = Address("unknown")
  }
  final case class Action(value: String) extends AnyVal

}
