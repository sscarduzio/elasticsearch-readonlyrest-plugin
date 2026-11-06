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
package tech.beshu.ror.unit.boot

import monix.execution.{ExecutionModel, Scheduler, UncaughtExceptionReporter}
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.boot.{RorSchedulers, SchedulerContextRestore}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

class SchedulerContextRestoreTests extends AnyWordSpec {

  "The main ROR scheduler" should {
    "call the context switch hook before it runs a task" in {
      callsContextSwitchHook(RorSchedulers.mainScheduler) should be(true)
    }
    "keep the context switch hook when it gets a new execution model" in {
      val scheduler = RorSchedulers.mainScheduler.withExecutionModel(ExecutionModel.AlwaysAsyncExecution)
      callsContextSwitchHook(scheduler) should be(true)
    }
    "keep the context switch hook when it gets a new uncaught exception reporter" in {
      val scheduler = RorSchedulers.mainScheduler.withUncaughtExceptionReporter(UncaughtExceptionReporter.default)
      callsContextSwitchHook(scheduler) should be(true)
    }
  }

  private def callsContextSwitchHook(scheduler: Scheduler): Boolean = {
    val hookCalled = new AtomicBoolean(false)
    val done = new CountDownLatch(1)
    SchedulerContextRestore.onContextSwitch.bind(Some(() => hookCalled.set(true))) {
      scheduler.execute(() => done.countDown())
    }
    if (!done.await(5, TimeUnit.SECONDS)) fail("The scheduler does not run the task")
    hookCalled.get()
  }

}
