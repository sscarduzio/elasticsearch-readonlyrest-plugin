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
package tech.beshu.ror.accesscontrol.utils

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.Result

// todo: think about it
object TaskResultOps {
  extension [B](result: Task[Result[B]]) {
    def flatMapT[C <: BlockContext](f: B => Task[Result[C]]): Task[Result[C]] =
      result.flatMap {
        case Result.Fulfilled(b) => f(b)
        case Result.Rejected(cause) => Task.pure(Result.Rejected(cause))
      }
  }
}
