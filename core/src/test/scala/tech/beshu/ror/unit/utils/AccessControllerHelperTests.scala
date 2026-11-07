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
package tech.beshu.ror.unit.utils

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.{CancelablePromise, ExecutionModel, Scheduler, UncaughtExceptionReporter}
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.AccessControllerFrames.runsInsideDoPrivileged
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.language.postfixOps

class AccessControllerHelperTests extends AnyWordSpec {

  "The scheduler of a privileged task" should {
    "run its tasks with privileges" in {
      runsPrivileged(privilegedScheduler) should be(true)
    }
    "keep the privileges when it gets a new execution model" in {
      val scheduler = privilegedScheduler.withExecutionModel(ExecutionModel.AlwaysAsyncExecution)
      runsPrivileged(scheduler) should be(true)
    }
    "keep the privileges when it gets a new uncaught exception reporter" in {
      val scheduler = privilegedScheduler.withUncaughtExceptionReporter(UncaughtExceptionReporter.default)
      runsPrivileged(scheduler) should be(true)
    }
    "report failures to its new uncaught exception reporter" in {
      val reported = new AtomicReference(Option.empty[Throwable])
      val failure = new Exception("test failure")
      privilegedScheduler
        .withUncaughtExceptionReporter(UncaughtExceptionReporter(ex => reported.set(Some(ex))))
        .reportFailure(failure)
      reported.get() should be(Some(failure))
    }
    "run with privileges the step after a thread outside of ROR completes a Monix promise" in {
      // A Monix promise calls back the task on the thread that completes it.
      val promise = CancelablePromise[Unit]()
      val privileged = doPrivileged(Task.fromCancelablePromise(promise) >> Task(runsInsideDoPrivileged()))(
        RorSchedulers.mainScheduler
      ).runToFuture(RorSchedulers.mainScheduler)
      val complete: Runnable = () => promise.success(())
      new Thread(complete).start()
      Await.result(privileged, 5.seconds) should be(true)
    }
  }

  private def privilegedScheduler: Scheduler = {
    doPrivileged(Task.deferAction(Task.now))(global).runSyncUnsafe(5 seconds)
  }

  // Returns true when the scheduler runs a task inside `AccessController.doPrivileged`.
  private def runsPrivileged(scheduler: Scheduler): Boolean = {
    val privileged = new AtomicBoolean(false)
    val done = new CountDownLatch(1)
    scheduler.execute { () =>
      privileged.set(runsInsideDoPrivileged())
      done.countDown()
    }
    if (!done.await(5, TimeUnit.SECONDS)) fail("The scheduler does not run the task")
    privileged.get()
  }

}
