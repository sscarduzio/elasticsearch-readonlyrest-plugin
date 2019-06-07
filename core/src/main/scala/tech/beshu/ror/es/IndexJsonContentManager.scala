package tech.beshu.ror.es

import monix.eval.Task
import tech.beshu.ror.es.IndexJsonContentManager.{ReadError, WriteError}

trait IndexJsonContentManager {

  def sourceOf(index: String, `type`: String, id: String): Task[Either[ReadError, java.util.Map[String, Any]]]

  def saveContent(index: String, `type`: String, id: String, content: java.util.Map[String, String]): Task[Either[WriteError, Unit]]
}

object IndexJsonContentManager {

  sealed trait ReadError
  case object ContentNotFound extends ReadError
  case object CannotReachContentSource extends ReadError

  sealed trait WriteError
  final case class CannotWriteToIndex(throwable: Throwable) extends WriteError
}
