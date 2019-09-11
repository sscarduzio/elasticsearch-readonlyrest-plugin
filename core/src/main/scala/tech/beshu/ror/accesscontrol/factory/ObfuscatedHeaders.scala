package tech.beshu.ror.accesscontrol.factory

import tech.beshu.ror.accesscontrol.blocks.LoggingContext
import tech.beshu.ror.accesscontrol.domain.Header

final case class ObfuscatedHeaders(headers:Set[Header.Name])
