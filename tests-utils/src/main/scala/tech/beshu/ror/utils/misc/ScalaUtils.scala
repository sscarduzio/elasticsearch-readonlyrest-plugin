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

import cats.implicits._
import cats.Functor
import monix.eval.Task

import scala.concurrent.duration.FiniteDuration
import scala.language.{higherKinds, implicitConversions}

object ScalaUtils {

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
    Stream.fill(times)(()).foreach(_ => action)
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

}
