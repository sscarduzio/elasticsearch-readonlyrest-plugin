package tech.beshu.ror.accesscontrol.factory

import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.configuration.LoggingContext

final case class ObfuscatedHeaders(headers:Set[Header.Name])
