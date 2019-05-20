package tech.beshu.ror.es

import monix.eval.Task

trait IndexContentProvider {

  def contentOf(index: String, `type`: String, id: String): Task[Either[IndexContentProvider.Error, String]]
}

object IndexContentProvider {

  sealed trait Error
  object Error {
    case object ContentNotFound extends Error
    case object CannotReachContentSource extends Error
  }
}
