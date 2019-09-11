package tech.beshu.ror.accesscontrol.factory

import cats.Show
import cats.implicits._
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.configuration.LoggingContext

object LoggingContextFactory {
  def create(obfuscatedHeaders: Option[ObfuscatedHeaders]): LoggingContext = new LoggingContext()(
    Show.show[Header] {
      case Header(name, _) if name === Header.Name.authorization => s"${name.show}=<OMITTED>"
      case Header(name, value) => s"${name.show}=${value.value.show}"
    }
  )
}
