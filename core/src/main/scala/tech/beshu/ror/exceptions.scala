package tech.beshu.ror

import tech.beshu.ror.boot.StartingFailure

object exceptions {

  class StartingFailureException(message: String, throwable: Throwable)
    extends RuntimeException(message, throwable) {

    def this(message: String) {
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
