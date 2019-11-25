package tech.beshu.ror.es.proxy

import java.util

import monix.eval.Task
import tech.beshu.ror.es.IndexJsonContentManager

// todo: implement
object ProxyIndexJsonContentManager extends IndexJsonContentManager {

  override def sourceOf(index: String,
                        `type`: String,
                        id: String): Task[Either[IndexJsonContentManager.ReadError, util.Map[String, _]]] =
    Task.now(Left(IndexJsonContentManager.CannotReachContentSource))

  override def saveContent(index: String,
                           `type`: String,
                           id: String,
                           content: util.Map[String, String]): Task[Either[IndexJsonContentManager.WriteError, Unit]] =
    Task.now(Right(()))
}
