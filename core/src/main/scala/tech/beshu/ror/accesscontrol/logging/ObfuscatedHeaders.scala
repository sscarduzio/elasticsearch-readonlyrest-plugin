package tech.beshu.ror.accesscontrol.logging

import tech.beshu.ror.accesscontrol.domain.Header

final case class ObfuscatedHeaders(headers:Set[Header.Name])
