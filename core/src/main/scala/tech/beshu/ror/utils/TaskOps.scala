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

import monix.eval.Task

import java.time.{Clock, Duration, Instant}
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.{Failure, Success, Try}

object TaskOps {

  extension [T](task: Task[T]) {

    def andThen[U](pf: PartialFunction[Try[T], U]): Task[T] = {
      task
        .map { t =>
          val success: Try[T] = Success(t)
          pf.applyOrElse(success, identity[Try[T]])
          t
        }
        .onErrorRecover { case e =>
          val failure: Try[T] = Failure[T](e)
          pf.applyOrElse(failure, identity[Try[T]])
          throw e
        }
    }

  }

  extension (taskCompanion: Task.type) {

    def measure[T](task: Task[T], logTimeMeasurement: FiniteDuration => Task[Unit])(
        implicit clock: Clock
    ): Task[T] =
      Task.defer {
        val startMeasurement = Instant.now(clock)
        val stopMeasurement = Task.suspend {
          val end = Instant.now(clock)
          val measurement = new FiniteDuration(Duration.between(startMeasurement, end).toMillis, MILLISECONDS)
          logTimeMeasurement(measurement)
        }
        task.redeemWith(
          ex => stopMeasurement.flatMap(_ => Task.raiseError(ex)),
          result => stopMeasurement.map(_ => result),
        )
      }

    // Use it for a future that a thread outside of ROR completes, for example an ES listener. After a result or an
    // error, the request continues on a ROR thread with its context, because the boundary comes after Monix restores
    // the Locals. `asyncBoundary` skips an error, so the error passes the boundary as a value.
    def fromEsFuture[T](future: Future[T]): Task[T] = Task.fromFuture(future).materialize.asyncBoundary.dematerialize

  }

}
