package tech.beshu.ror.accesscontrol.logging

import cats.Show
import cats.implicits._
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.show.logs._

object ObfuscatedHeaderShowFactory {
  def create(obfuscatedHeaders: Set[Header.Name]): Show[Header] = {
    Show.show[Header] {
      case Header(name, _) if obfuscatedHeaders.contains(name) => s"${name.show}=<OMITTED>"
      case Header(name, value) => s"${name.show}=${value.value.show}"
    }
  }
}
