package tech.beshu.ror.es.scala

import org.elasticsearch.ElasticsearchException
import tech.beshu.ror.boot.StartingFailure

class StartingFailureException(message: String, throwable: Throwable)
  extends ElasticsearchException(message, throwable) {

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
}
