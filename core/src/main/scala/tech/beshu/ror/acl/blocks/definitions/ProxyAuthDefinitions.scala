package tech.beshu.ror.acl.blocks.definitions

import cats.Eq
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.blocks.definitions.ProxyAuth.Name

final case class ProxyAuthDefinitions(proxyAuths: Set[ProxyAuth]) extends AnyVal

final case class ProxyAuth(name: Name, userIdHeader: Header.Name)

object ProxyAuth {
  final case class Name(value: String) extends AnyVal

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
}