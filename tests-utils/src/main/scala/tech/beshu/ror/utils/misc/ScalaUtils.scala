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
import monix.eval.Task

import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Success, Try}

object ScalaUtils {

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

  def retry(times: Int)(action: Unit): Unit = {
    LazyList
      .fill(times)(())
      .foldLeft(Success(()): Try[Unit]) {
        case (Success(_), _) => Try(action)
        case (failure, _) => failure
      }
      .get
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
