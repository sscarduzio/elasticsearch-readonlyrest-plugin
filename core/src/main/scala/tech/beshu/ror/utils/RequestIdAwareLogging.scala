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
package tech.beshu.ror.utils

import cats.implicits.toShow
import monix.eval.Task
import org.apache.logging.log4j.scala.Logger
import org.apache.logging.log4j.spi.ExtendedLogger
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.logging.ResponseContext
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.RequestIdAwareLogging.RorLogger

trait RequestIdAwareLogging {

  private lazy val underlyingLogger: Logger = Logger(getClass)

  val logger: RorLogger = new RorLogger(underlyingLogger)

  val noRequestIdLogger: Logger = underlyingLogger

  given [B <: BlockContext](using value: ResponseContext[B]): RequestId =
    value.requestContext.id.toRequestId

  given [B <: BlockContext](using value: B): RequestId =
    value.requestContext.id.toRequestId

  given (using value: RequestContext.Id): RequestId =
    value.toRequestId

  given (using value: RequestContext): RequestId =
    value.id.toRequestId

}

object RequestIdAwareLogging {

  final class RorLogger(private val log: Logger) {

    lazy val delegate: ExtendedLogger = log.delegate

    def trace(msg: => String)(implicit rid: RequestId): Unit =
      if (log.delegate.isTraceEnabled)
        log.trace(buildMsg(msg, rid))

    def trace(msg: => String, t: Throwable)(implicit rid: RequestId): Unit =
      if (log.delegate.isTraceEnabled)
        log.trace(buildMsg(msg, rid), t)

    def debug(msg: => String)(implicit rid: RequestId): Unit =
      if (log.delegate.isDebugEnabled)
        log.debug(buildMsg(msg, rid))

    def debug(msg: => String, t: Throwable)(implicit rid: RequestId): Unit =
      if (log.delegate.isDebugEnabled)
        log.debug(buildMsg(msg, rid), t)

    def info(msg: => String)(implicit rid: RequestId): Unit =
      if (log.delegate.isInfoEnabled)
        log.info(buildMsg(msg, rid))

    def info(msg: => String, t: Throwable)(implicit rid: RequestId): Unit =
      if (log.delegate.isInfoEnabled)
        log.info(buildMsg(msg, rid), t)

    def warn(msg: => String)(implicit rid: RequestId): Unit =
      if (log.delegate.isWarnEnabled)
        log.warn(buildMsg(msg, rid))

    def warn(msg: => String, t: Throwable)(implicit rid: RequestId): Unit =
      if (log.delegate.isWarnEnabled)
        log.warn(buildMsg(msg, rid), t)

    def error(msg: => String)(implicit rid: RequestId): Unit =
      if (log.delegate.isErrorEnabled)
        log.error(buildMsg(msg, rid))

    def error(msg: => String, t: Throwable)(implicit rid: RequestId): Unit =
      if (log.delegate.isErrorEnabled)
        log.error(buildMsg(msg, rid), t)

    def errorEx(msg: => String, t: Throwable)(implicit rid: RequestId): Unit =
      if (log.delegate.isDebugEnabled)
        log.error(buildMsg(msg, rid), t)
      else
        log.error(buildMsg(s"$msg; ${t.getMessage}", rid))

    def warnEx(msg: => String, t: Throwable)(implicit rid: RequestId): Unit =
      if (log.delegate.isDebugEnabled)
        log.warn(buildMsg(msg, rid), t)
      else
        log.warn(buildMsg(s"$msg; ${t.getMessage}", rid))

    def dInfo(msg: String)(implicit rid: RequestId): Task[Unit] = {
      Task.delay(info(msg))
    }

    def dWarn(msg: String)(implicit rid: RequestId): Task[Unit] = {
      Task.delay(warn(msg))
    }

    def dDebug(msg: String)(implicit rid: RequestId): Task[Unit] = {
      Task.delay(debug(msg))
    }

    def dError(msg: String)(implicit rid: RequestId): Task[Unit] = {
      Task.delay(error(msg))
    }

    private def buildMsg(msg: String, rid: RequestId): String = {
      import tech.beshu.ror.implicits.requestIdShow
      val ridStr = rid.show
      val sb = new java.lang.StringBuilder(ridStr.length + msg.length + 4)
      sb.append('[').append(ridStr).append("] ").append(msg)
      sb.toString
    }
  }
}
