/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.IndexJsonContentService

// todo: implement
object ProxyIndexJsonContentService extends IndexJsonContentService {

  override def sourceOf(index: IndexName.Full,
                        id: String): Task[Either[IndexJsonContentService.ReadError, Map[String, String]]] =
    Task.now(Left(IndexJsonContentService.CannotReachContentSource))

  override def saveContent(index: IndexName.Full,
                           id: String,
                           content: Map[String, String]): Task[Either[IndexJsonContentService.WriteError, Unit]] =
    Task.now(Right(()))
}
