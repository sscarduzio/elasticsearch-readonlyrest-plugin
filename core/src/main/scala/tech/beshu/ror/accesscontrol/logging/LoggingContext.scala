package tech.beshu.ror.accesscontrol.logging

import cats.Show
import tech.beshu.ror.accesscontrol.domain.Header

final class LoggingContext(implicit val showHeader:Show[Header])
