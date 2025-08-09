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
import monix.execution.{Cancelable, ExecutionModel, Scheduler, UncaughtExceptionReporter}

import java.security.{AccessController, PrivilegedAction}
import scala.concurrent.duration.TimeUnit

object AccessControllerHelper {
  def doPrivileged[T](action: => T): T = {
    AccessController.doPrivileged(new PrivilegedAction[T] {
      override def run(): T = action
    })
  }

  def doPrivileged[T](actionTask: Task[T])
                     (implicit scheduler: Scheduler): Task[T] = {
    actionTask.executeOn(PrivilegedScheduler(scheduler))
  }

  private final class PrivilegedScheduler(underlying: Scheduler)
    extends Scheduler {

    def execute(r: Runnable): Unit =
      underlying.execute(wrap(r))

    def scheduleOnce(d: Long, u: TimeUnit, r: Runnable): Cancelable =
      underlying.scheduleOnce(d, u, wrap(r))

    def scheduleAtFixedRate(i: Long, p: Long, u: TimeUnit, r: Runnable): Cancelable =
      underlying.scheduleAtFixedRate(i, p, u, wrap(r))

    def scheduleWithFixedDelay(i: Long, p: Long, u: TimeUnit, r: Runnable): Cancelable =
      underlying.scheduleWithFixedDelay(i, p, u, wrap(r))

    def executionModel: ExecutionModel = underlying.executionModel

    def reportFailure(t: Throwable): Unit = underlying.reportFailure(t)

    override def clockRealTime(unit: TimeUnit): Long = underlying.clockRealTime(unit)

    override def clockMonotonic(unit: TimeUnit): Long = underlying.clockMonotonic(unit)

    override def withExecutionModel(em: ExecutionModel): Scheduler = underlying.withExecutionModel(em)

    override def withUncaughtExceptionReporter(r: UncaughtExceptionReporter): Scheduler =
      underlying.withUncaughtExceptionReporter(underlying)

    private def wrap(r: Runnable): Runnable = {
      () => doPrivileged(r.run())
    }

  }
}
