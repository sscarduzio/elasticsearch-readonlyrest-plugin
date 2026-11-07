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
package tech.beshu.ror.boot

import monix.eval.Task
import monix.execution.misc.{CanBindLocals, Local}
import monix.execution.{Cancelable, ExecutionModel, Features, Scheduler, UncaughtExceptionReporter}

import scala.concurrent.duration.TimeUnit
import scala.util.{Failure, Success, Try, Using}

object SchedulerContextRestore {

  private val onContextSwitch: Local[Option[() => AutoCloseable]] = Local(None)

  extension [A](task: Task[A]) {

    // Runs the task of one request. Before each step that a ROR scheduler runs, `restoreContext` gives the thread the
    // context of this request. After the step, the scheduler closes the result, which gives the thread its previous
    // context back. Concurrent requests do not get the context of each other, also after a task wakes up another task,
    // for example when it releases a semaphore permit. If `restoreContext` fails, it must leave the context of the
    // thread as it was.
    def runAsyncWithContextRestore(restoreContext: () => AutoCloseable)(callback: Either[Throwable, A] => Unit)(
        implicit scheduler: Scheduler
    ): Cancelable =
      onContextSwitch.bind(Some(restoreContext)) {
        task.runAsyncOpt(callback)(scheduler, Task.defaultOptions.enableLocalContextPropagation)
      }(CanBindLocals.synchronous)

  }

  private[boot] final class ContextRestoringScheduler(underlying: Scheduler) extends Scheduler {

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

    // With the tracing feature, Monix does not wrap this scheduler in its own TracingScheduler. That one runs
    // trampolined steps on the calling thread, so they skip `wrap`. This scheduler does not batch: `wrap` turns each
    // step into a plain `Runnable`, and the underlying scheduler forks it.
    override def features: Features = underlying.features.diff(Features(Scheduler.BATCHING))

    override def withExecutionModel(em: ExecutionModel): Scheduler =
      new ContextRestoringScheduler(underlying.withExecutionModel(em))

    override def withUncaughtExceptionReporter(r: UncaughtExceptionReporter): Scheduler =
      new ContextRestoringScheduler(underlying.withUncaughtExceptionReporter(r))

    private def wrap(r: Runnable): Runnable = { () =>
      onContextSwitch.apply() match {
        case Some(restoreContext) => runInContext(restoreContext, r)
        case None                 => r.run()
      }
    }

    // The task stops when one of its steps does not run. Thus, the step also runs when `restoreContext` fails. Then it
    // runs in the context of the thread. The scheduler reports the failure, because it gets only the step and cannot
    // fail the task.
    private def runInContext(restoreContext: () => AutoCloseable, r: Runnable): Unit =
      Try(restoreContext()) match {
        case Success(context) => Using.resource(context)(_ => r.run())
        case Failure(ex)      =>
          reportFailure(ex)
          r.run()
      }

  }

}
