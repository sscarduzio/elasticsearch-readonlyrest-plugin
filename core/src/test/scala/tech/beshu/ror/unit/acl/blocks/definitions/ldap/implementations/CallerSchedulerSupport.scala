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
package tech.beshu.ror.unit.acl.blocks.definitions.ldap.implementations

import monix.eval.{Fiber, Task}
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Try

trait CallerSchedulerSupport {

  protected def withSingleThreadScheduler(test: Scheduler => Unit): Unit = {
    val scheduler = Scheduler.singleThread("ldap-test-caller")
    try test(scheduler)
    finally scheduler.shutdown()
  }

  protected def startOn[A](scheduler: Scheduler, call: Task[A]): Fiber[A] = {
    call.start.runSyncUnsafe(5 seconds)(scheduler, CanBlock.permit)
  }

  protected def runsOtherTask(scheduler: Scheduler): Boolean = {
    Try(Task.unit.executeAsync.runSyncUnsafe(5 seconds)(scheduler, CanBlock.permit)).isSuccess
  }

  protected def resultOf[A](call: Fiber[A]): A = call.join.runSyncUnsafe(30 seconds)

}
