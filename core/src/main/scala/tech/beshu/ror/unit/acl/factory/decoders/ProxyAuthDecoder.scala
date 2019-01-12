package tech.beshu.ror.unit.acl.factory.decoders

import cats.{Eq, Show}
import io.circe.Decoder
import tech.beshu.ror.unit.acl.factory.decoders.ProxyAuth.Name
import tech.beshu.ror.commons.aDomain.Header

final case class ProxyAuth(name: Name, userIdHeader: Header.Name)
object ProxyAuth {
  final case class Name(value: String) extends AnyVal

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
  implicit val nameShow: Show[Name] = Show.show(_.value)
}

object ProxyAuthDecoder {
  implicit val proxyAuthNameDecoder: Decoder[ProxyAuth.Name] = Decoder.decodeString.map(ProxyAuth.Name.apply)
}
