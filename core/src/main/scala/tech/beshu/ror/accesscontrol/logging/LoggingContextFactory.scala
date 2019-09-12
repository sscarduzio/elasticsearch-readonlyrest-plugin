package tech.beshu.ror.accesscontrol.logging

import cats.Show
import cats.implicits._
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.show.logs._

object LoggingContextFactory {
  def create(obfuscatedHeaders: Option[ObfuscatedHeaders]): LoggingContext = {
    val headers = getHeaders(obfuscatedHeaders, default)
    new LoggingContext()(createShow(headers))
  }
  private val default = Set(Header.Name.authorization)

  private def getHeaders(obfuscatedHeaders: Option[ObfuscatedHeaders], default:Set[Header.Name]) =
    obfuscatedHeaders.map(_.headers).getOrElse(default)

  private def createShow(headers: Set[Header.Name]) =
    Show.show[Header] {
      case Header(name, _) if headers.contains(name) => s"${name.show}=<OMITTED>"
      case Header(name, value) => s"${name.show}=${value.value.show}"
    }
}
