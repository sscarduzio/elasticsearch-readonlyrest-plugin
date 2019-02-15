package tech.beshu.ror.audit

import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

sealed trait AuditResponseContext {
  def requestContext: AuditRequestContext

  val duration: FiniteDuration = FiniteDuration(
    Duration.between(requestContext.timestamp, Instant.now()).toMillis,
    TimeUnit.MILLISECONDS
  )
}

object AuditResponseContext {

  final case class Allowed(requestContext: AuditRequestContext,
                           verbosity: Verbosity,
                           reason: String)
    extends AuditResponseContext

  final case class ForbiddenBy(requestContext: AuditRequestContext,
                               verbosity: Verbosity,
                               reason: String)
    extends AuditResponseContext

  final case class Forbidden(requestContext: AuditRequestContext)
    extends AuditResponseContext

  final case class Errored(requestContext: AuditRequestContext,
                           cause: Throwable)
    extends AuditResponseContext

  final case class NotFound(requestContext: AuditRequestContext,
                            cause: Throwable)
    extends AuditResponseContext

  sealed trait Verbosity
  object Verbosity {
    case object Info extends Verbosity
    case object Error extends Verbosity
  }
}