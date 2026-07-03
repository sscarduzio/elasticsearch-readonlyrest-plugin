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

import cats.Functor
import cats.implicits.*
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task

import better.files.{File => BetterFile}
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.Using

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

  implicit def javaDurationToFiniteDuration(interval: Duration): FiniteDuration =
    FiniteDuration(interval.toMillis, TimeUnit.MILLISECONDS)

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

  // One shared io scheduler for all runWithTimeout calls (don't allocate a pool per call). Daemon
  // threads, so it never blocks JVM exit; abandoned-on-timeout work dies with the short-lived leg JVM.
  private lazy val timeoutScheduler: monix.execution.Scheduler =
    monix.execution.Scheduler.io(name = "ror-it-timeout-guard")

  /**
   * Run a blocking `action`, abandoning it if it doesn't finish within `timeout`. Bounds blocking ES
   * teardown so a WEDGED ES can't hang the worker JVM for hours. On timeout the Task is abandoned (the
   * blocking call can't be interrupted, but dies with the leg JVM) and a TimeoutException is thrown.
   */
  def runWithTimeout[A](label: String, timeout: FiniteDuration)(action: => A): A = {
    Task
      .delay(action)
      .timeoutWith(
        timeout,
        new java.util.concurrent.TimeoutException(
          s"'$label' did not complete within $timeout — abandoning it (likely a wedged ES/container call)."
        )
      )
      .runSyncUnsafe()(timeoutScheduler, implicitly)
  }

  /**
   * Best-effort execution: runs `step` with a timeout, logs and swallows any failure so teardown
   * continues. Use for cleanup steps where a failure must not abort the rest of the teardown sequence.
   */
  def bestEffort(name: String, timeout: FiniteDuration)(step: => Unit): Unit = {
    try runWithTimeout(name, timeout)(step)
    catch {
      case NonFatal(e) =>
        logger.error(s"'$name' failed/timed out — continuing cleanup", e)
    }
  }

  /**
   * SHA-256 hex digest of a file's content. Returns lowercase hex string (no prefix).
   * Byte-identical to `better.files.File#sha256` — safe to replace it anywhere.
   */
  def sha256(file: BetterFile): String = {
    val md = MessageDigest.getInstance("SHA-256")
    updateDigestFromFile(md, file.newInputStream)
    md.digest().map("%02x".format(_)).mkString
  }

  /**
   * SHA-512 hex digest of a file's content. Returns lowercase hex string (no prefix).
   * Used for download integrity verification against Elastic's published `.sha512` checksums.
   */
  def sha512(file: BetterFile): String = {
    val md = MessageDigest.getInstance("SHA-512")
    updateDigestFromFile(md, file.newInputStream)
    md.digest().map("%02x".format(_)).mkString
  }

  private def updateDigestFromFile(md: MessageDigest, inputStream: => InputStream): Unit = {
    Using.resource(inputStream) { in =>
      val buffer = new Array[Byte](64 * 1024)
      Iterator.continually(in.read(buffer)).takeWhile(_ != -1).foreach(md.update(buffer, 0, _))
    }
  }

  def retryBackoff[A](source: Task[A], maxRetries: Int, firstDelay: FiniteDuration, backOffScaler: Int): Task[A] = {
    source.onErrorHandleWith { case ex: Exception =>
      if (maxRetries > 0)
        retryBackoff(source, maxRetries - 1, firstDelay * backOffScaler, backOffScaler)
          .delayExecution(firstDelay)
      else
        Task.raiseError(ex)
    }
  }

  def waitForCondition(conditionDescription: String)(condition: => Boolean)(
      implicit timeout: FiniteDuration = 20 seconds
  ): Unit = {
    import monix.execution.Scheduler.Implicits.global
    val retriesCount = timeout.toSeconds.toInt + 1
    retryBackoff(
      Task.delay(
        if (condition) ()
        else
          throw new Exception(s"Condition '$conditionDescription' is not fulfilled. Cannot wait longer than $timeout")
      ),
      maxRetries = retriesCount,
      firstDelay = 1 seconds,
      backOffScaler = 1
    ).runSyncUnsafe()
  }

}
