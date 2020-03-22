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

import monix.execution.CancelablePromise

import scala.util.{Failure, Success, Try}

object ScalaJavaHelper {

  def force[T](value: Try[T]): T = value match {
    case Success(v) => v
    case Failure(exception) => throw exception
  }

  def newCancelablePromise[T]: CancelablePromise[T] =
    CancelablePromise[T]()

  def getOrElse[A, B](either: Either[A, B], toException: A => Exception): B = {
    either.fold(
      left => throw toException(left),
      identity,
    )
  }
}
