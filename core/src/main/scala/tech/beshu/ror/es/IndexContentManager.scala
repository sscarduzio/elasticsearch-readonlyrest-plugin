package tech.beshu.ror.es

import monix.eval.Task
import tech.beshu.ror.es.IndexContentManager.{ReadError, WriteError}

trait IndexContentManager {

  def contentOf(index: String, `type`: String, id: String): Task[Either[ReadError, String]]

  def saveContent(index: String, `type`: String, id: String, content: String): Task[Either[WriteError, Unit]]
}

object IndexContentManager {

  sealed trait ReadError
  case object ContentNotFound extends ReadError
  case object CannotReachContentSource extends ReadError

  sealed trait WriteError
  final case class CannotWriteToIndex(throwable: Throwable) extends WriteError
}
