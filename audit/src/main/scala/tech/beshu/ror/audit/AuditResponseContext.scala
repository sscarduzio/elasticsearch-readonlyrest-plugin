/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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

  final case class RequestedIndexNotExist(requestContext: AuditRequestContext)
    extends AuditResponseContext

  final case class Errored(requestContext: AuditRequestContext,
                           cause: Throwable)
    extends AuditResponseContext

  sealed trait Verbosity
  object Verbosity {
    case object Info extends Verbosity
    case object Error extends Verbosity
  }
}