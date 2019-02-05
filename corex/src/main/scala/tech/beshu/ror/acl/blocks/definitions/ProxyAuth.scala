package tech.beshu.ror.acl.blocks.definitions

import cats.{Eq, Show}
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.blocks.definitions.ProxyAuth.Name
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

final case class ProxyAuth(id: ProxyAuth#Id, userIdHeader: Header.Name) extends Item {
  override type Id = Name
  override implicit val show: Show[Name] = ProxyAuth.nameShow
}

object ProxyAuth {
  final case class Name(value: String) extends AnyVal

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
  implicit val nameShow: Show[Name] = Show.show(_.value)
}