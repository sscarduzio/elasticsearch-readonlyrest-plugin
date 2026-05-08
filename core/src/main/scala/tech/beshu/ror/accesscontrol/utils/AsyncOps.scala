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

import cats.effect.{Async, ContextShift}

import scala.concurrent.Future

object AsyncOps {
  extension [F[_]](async: Async[F])
    def deferFuture[T](value: => Future[T])
                      (using ContextShift[F]): F[T] = {
      Async.fromFuture(async.delay(value))(async, ContextShift[F])
    }
}
