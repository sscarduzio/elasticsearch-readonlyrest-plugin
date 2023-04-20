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
package tech.beshu.ror

import tech.beshu.ror.boot.ReadonlyRest.StartingFailure

object exceptions {

  class StartingFailureException(message: String, throwable: Throwable)
    extends RuntimeException(message, throwable) {

    def this(message: String) = {
      this(message, null)
    }
  }

  object StartingFailureException {
    def from(failure: StartingFailure): StartingFailureException = {
      failure.throwable match {
        case Some(throwable) => new StartingFailureException(failure.message, throwable)
        case None => new StartingFailureException(failure.message)
      }
    }
    def from(throwable: Throwable): StartingFailureException = {
      new StartingFailureException("Cannot start ReadonlyREST", throwable)
    }
  }

  class SecurityPermissionException(msg: String, cause: Throwable = null)
    extends RuntimeException(msg, cause)
}
