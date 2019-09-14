package tech.beshu.ror.accesscontrol.logging

import tech.beshu.ror.accesscontrol.domain.Header

final case class LoggingContext(obfuscatedHeaders: Set[Header.Name])
