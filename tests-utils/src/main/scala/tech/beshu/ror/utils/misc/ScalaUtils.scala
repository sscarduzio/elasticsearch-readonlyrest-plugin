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
package tech.beshu.ror.utils.misc

import java.time.Duration
import cats.Functor
import cats.implicits.*
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task

import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try
import scala.util.control.NonFatal

object ScalaUtils extends LazyLogging {

  implicit class StringOps(val value: String) extends AnyVal {
    def stripMarginAndReplaceWindowsLineBreak: String = {
      value.stripMargin.replace("\r\n", "\n")
    }
  }
  
  implicit class StringDateTimeOps(val value: String) extends AnyVal {
    def isInIsoDateTimeFormat: Boolean = {
      isInDateTimeFormat(DateTimeFormatter.ISO_DATE_TIME)
    }

    def isInDateTimeFormat(format: DateTimeFormatter): Boolean = {
      Try(format.parse(value)).toOption.isDefined
    }
  }
  
  implicit class StringListOps(val list: List[String]) extends AnyVal {
    def mkJsonStringArray: String =
      list.map(e => s""""$e"""").mkString("[", ",", "]")
  }

  implicit class ListOps[T](val list: List[T]) extends AnyVal {
    def partitionByIndexMod2: (List[T], List[T]) = {
      list.zipWithIndex.partition(_._2 % 2 == 0).bimap(_.map(_._1), _.map(_._1))
    }
  }

  implicit class AutoCloseableOps[A <: AutoCloseable](val value: A) {
    def bracket[B](convert: A => B): B = {
      try {
        convert(value)
      } finally {
        value.close()
      }
    }
  }

  implicit class AutoClosableMOps[A <: AutoCloseable, M[_]: Functor](val value: M[A]) {
    def bracket[B](convert: A => B): M[B] = {
      value.map(v => AutoCloseableOps(v).bracket(convert))
    }
  }

  implicit def finiteDurationToJavaDuration(interval: FiniteDuration): Duration = Duration.ofMillis(interval.toMillis)

  implicit def javaDurationToFiniteDuration(interval: Duration): FiniteDuration = FiniteDuration(interval.toMillis, TimeUnit.MILLISECONDS)

  def retry(times: Int, cleanBeforeRetrying: => Unit = ())(action: => Unit): Unit = {
    @tailrec
    def loop(attempt: Int): Unit = {
      try {
        action
      } catch {
        case NonFatal(e) =>
          val nextAttempt = attempt + 1
          if (nextAttempt > times) {
            logger.error(s"Attempt $attempt failed: ${e.getMessage}. Retries exhausted, failing with exception.")
            throw e
          } else {
            logger.error(s"Attempt $attempt failed: ${e.getMessage}", e)
            logger.warn(s"Starting cleaning after failed attempt")
            cleanBeforeRetrying
            logger.warn(s"Retrying...")
            loop(nextAttempt)
          }
      }
    }

    loop(1)
  }

  /**
   * Run `action` on a daemon thread and abandon it if it does not finish within `timeout`.
   *
   * Why this exists: the singleton-ES teardown (cleanUpContainer / container.stop / blocking ES REST
   * ops) had no time bound, so a WEDGED (not dead) ES could hang the worker JVM forever. On the CI
   * agent that produced "stopped hearing from agent" leg failures that ran 300+ minutes — 3-4x past
   * the 120-min job timeout — because nothing ever returned. Bounding each blocking teardown step
   * here makes a leg self-terminate in minutes instead of hanging for hours.
   *
   * On timeout we interrupt the worker thread (best-effort — blocking socket reads may ignore it),
   * log, and throw so the caller can fail fast / force-kill the container rather than wait forever.
   *
   * Residual: an un-interruptible worker keeps running (as a daemon) until the JVM exits, still
   * holding the wedged connection. Acceptable here because the IT worker JVM is short-lived per leg —
   * it exits at end-of-leg and the leaked thread dies with it; we never accumulate across legs. If
   * teardown timeouts ever become common, the real reclaim is `docker rm -f` on the container, not a
   * thread interrupt.
   */
  def runWithTimeout[A](label: String, timeout: FiniteDuration)(action: => A): A = {
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, A]]()
    val worker = new Thread(() => {
      // Catch Throwable, not just NonFatal: a fatal error (OOM/StackOverflow/Interrupted) inside
      // `action` must still be RECORDED, else `result` stays null and the match below would throw a
      // misleading MatchError instead of the real cause. This is a throwaway teardown thread.
      result.set(try Right(action) catch { case e: Throwable => Left(e) })
    }, s"timeout-guard-$label")
    worker.setDaemon(true)
    worker.start()
    worker.join(timeout.toMillis)
    if (worker.isAlive) {
      worker.interrupt()
      logger.error(s"'$label' did not complete within $timeout — abandoning it (likely a wedged ES/container call).")
      throw new java.util.concurrent.TimeoutException(s"'$label' timed out after $timeout")
    }
    result.get() match {
      case null => throw new IllegalStateException(s"'$label' worker exited without setting a result")
      case Right(a) => a
      case Left(e) => throw e
    }
  }

  def retryBackoff[A](source: Task[A],
                      maxRetries: Int,
                      firstDelay: FiniteDuration,
                      backOffScaler: Int): Task[A] = {
    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          retryBackoff(source, maxRetries - 1, firstDelay * backOffScaler, backOffScaler)
            .delayExecution(firstDelay)
        else
          Task.raiseError(ex)
    }
  }

  def waitForCondition(conditionDescription: String)
                      (condition: => Boolean)
                      (implicit timeout: FiniteDuration = 20 seconds): Unit = {
    import monix.execution.Scheduler.Implicits.global
    val retriesCount = timeout.toSeconds.toInt + 1
    retryBackoff(
      Task.delay(
        if(condition) ()
        else throw new Exception(s"Condition '$conditionDescription' is not fulfilled. Cannot wait longer than $timeout")
      ),
      maxRetries = retriesCount,
      firstDelay = 1 seconds,
      backOffScaler = 1
    ).runSyncUnsafe()
  }

}
