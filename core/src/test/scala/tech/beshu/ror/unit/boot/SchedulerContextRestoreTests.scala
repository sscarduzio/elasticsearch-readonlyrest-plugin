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

import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.{CancelablePromise, ExecutionModel, Scheduler, UncaughtExceptionReporter}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.boot.SchedulerContextRestore.runAsyncWithContextRestore
import tech.beshu.ror.utils.TaskOps.fromEsFuture

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}

class SchedulerContextRestoreTests extends AnyWordSpec with Eventually {

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

  "The task of a request" should {
    "continue with its own context after another request starts" in {
      val requestAContinues = Promise[Unit]()
      val requestA = runRequest("A", Task.fromFuture(requestAContinues.future))
      resultOf(runRequest("B", Task.unit)) should be("B")
      requestAContinues.success(())
      resultOf(requestA) should be("A")
    }
    "continue with its own context after it waits for a semaphore permit" in {
      val semaphore = Semaphore[Task](1).runSyncUnsafe()
      val firstReleasesPermit = Promise[Unit]()
      val first = runRequest("R0", semaphore.withPermit(blockingStep(Task.fromFuture(firstReleasesPermit.future))))
      eventually(semaphore.available.runSyncUnsafe() should be(0))
      val waiting = List("R1", "R2", "R3").map(runRequest(_, semaphore.withPermit(blockingStep(Task.unit))))
      eventually(semaphore.count.runSyncUnsafe() should be(-3))
      firstReleasesPermit.success(())
      (first :: waiting).map(resultOf) should be(List("R0", "R1", "R2", "R3"))
    }
    "not give its context to a task that runs outside of a request" in {
      val restoresOfRequest = new AtomicInteger(0)
      val request = Promise[Unit]()
      Task.shift
        .runAsyncWithContextRestore(restoreThatRuns(restoresOfRequest.incrementAndGet()))(r =>
          request.complete(r.toTry)
        )(RorSchedulers.mainScheduler)
      resultOf(request.future)
      val restoresDuringRequest = restoresOfRequest.get()
      resultOf(Task.shift.runToFuture(RorSchedulers.mainScheduler))
      restoresOfRequest.get() should be(restoresDuringRequest)
    }
    "give the thread its previous context back after each step" in {
      val restores = new AtomicInteger(0)
      val contextsNotGivenBack = new AtomicInteger(0)
      val request = Promise[Unit]()
      (Task.shift >> blockingStep(Task.unit) >> Task.shift)
        .runAsyncWithContextRestore { () =>
          restores.incrementAndGet()
          contextsNotGivenBack.incrementAndGet()
          () => contextsNotGivenBack.decrementAndGet(): Unit
        }(r => request.complete(r.toTry))(RorSchedulers.mainScheduler)
      resultOf(request.future)
      restores.get() should be > 0
      eventually(contextsNotGivenBack.get() should be(0))
    }
    "not run its next step on the thread outside of ROR that completes its Monix promise" in {
      // A Monix promise calls back the task on the thread that completes it.
      val promise = CancelablePromise[Unit]()
      val (thread, _) =
        resultOf(runRequestCompletedOutsideOfRor("A", Task.fromCancelablePromise(promise), () => promise.success(())))
      thread should not be outsideOfRorThreadName
    }
    "continue with its own context after a thread outside of ROR completes its future" in {
      val promise = Promise[Unit]()
      val (thread, context) =
        resultOf(runRequestCompletedOutsideOfRor("A", Task.fromEsFuture(promise.future), () => promise.success(())))
      thread should not be outsideOfRorThreadName
      context should be("A")
    }
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(20, Millis)))

  // Stands for the ES thread context. The context restore of a request puts the name of the request in it.
  private val threadContext = new ThreadLocal[String]

  // Closing the result gives the thread its previous context back.
  private def restoreAs(name: String): () => AutoCloseable = () => {
    val previousContext = threadContext.get()
    threadContext.set(name)
    () => threadContext.set(previousContext)
  }

  private def restoreThatRuns(onRestore: => Unit): () => AutoCloseable = () => {
    onRestore
    () => ()
  }

  // Runs `step` as the task of the request `name`. The result is the thread context after the next thread switch.
  private def runRequest(name: String, step: Task[Unit]): Future[String] = {
    val result = Promise[String]()
    (step >> Task.shift >> Task(threadContext.get()))
      .runAsyncWithContextRestore(restoreAs(name))(r => result.complete(r.toTry))(
        RorSchedulers.mainScheduler
      )
    result.future
  }

  // Runs the request `name`. Its first step is `waitFor`, which ends when a thread outside of ROR calls `complete`. That
  // thread has its own context, as the thread of an ES listener has. The result is the thread and the context of the
  // next step.
  private def runRequestCompletedOutsideOfRor(
      name: String,
      waitFor: Task[Unit],
      complete: () => Unit
  ): Future[(String, String)] = {
    val result = Promise[(String, String)]()
    (waitFor >> Task((Thread.currentThread().getName, threadContext.get())))
      .runAsyncWithContextRestore(restoreAs(name))(r => result.complete(r.toTry))(RorSchedulers.mainScheduler)
    val completeWithOwnContext: Runnable = () => {
      threadContext.set("ES")
      complete()
    }
    new Thread(completeWithOwnContext, outsideOfRorThreadName).start()
    result.future
  }

  private val outsideOfRorThreadName = "outside-of-ror"

  private def blockingStep(step: Task[Unit]): Task[Unit] = step.executeOn(RorSchedulers.blockingScheduler)

  private def resultOf[A](call: Future[A]): A = Await.result(call, 10.seconds)

  private def callsContextSwitchHook(scheduler: Scheduler): Boolean = {
    val hookCalled = new AtomicBoolean(false)
    val done = Promise[Unit]()
    Task.unit.executeAsync
      .runAsyncWithContextRestore(restoreThatRuns(hookCalled.set(true)))(r => done.complete(r.toTry))(scheduler)
    resultOf(done.future)
    hookCalled.get()
  }

}
