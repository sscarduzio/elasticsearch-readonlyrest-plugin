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
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

class TaskOps[T](val task: Task[T]) extends AnyVal {

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

object TaskOps {
  implicit def from[T](task: Task[T]): TaskOps[T] = new TaskOps[T](task)
}
