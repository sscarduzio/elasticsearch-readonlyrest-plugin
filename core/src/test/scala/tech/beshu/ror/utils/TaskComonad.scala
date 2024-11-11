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

import cats.Comonad
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.*
import scala.language.postfixOps

class TaskComonad(timeout: FiniteDuration)
                 (implicit scheduler: Scheduler)
  extends Comonad[Task] {

  override def extract[A](x: Task[A]): A = x.runSyncUnsafe(timeout)

  override def coflatMap[A, B](fa: Task[A])(f: Task[A] => B): Task[B] = fa.map { a => f(Task.pure(a)) }

  override def map[A, B](fa: Task[A])(f: A => B): Task[B] = fa.map(f)
}

object TaskComonad {
  implicit val wait30SecTaskComonad: Comonad[Task] =
    new TaskComonad(30 seconds)(monix.execution.Scheduler.Implicits.global)
}