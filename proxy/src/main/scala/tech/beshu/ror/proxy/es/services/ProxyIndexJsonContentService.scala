/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import java.util

import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.IndexJsonContentService

// todo: implement
object ProxyIndexJsonContentService extends IndexJsonContentService {

  override def sourceOf(index: ClusterIndexName,
                        id: String): Task[Either[IndexJsonContentService.ReadError, util.Map[String, _]]] =
    Task.now(Left(IndexJsonContentService.CannotReachContentSource))

  override def saveContent(index: ClusterIndexName,
                           id: String,
                           content: util.Map[String, String]): Task[Either[IndexJsonContentService.WriteError, Unit]] =
    Task.now(Right(()))
}
