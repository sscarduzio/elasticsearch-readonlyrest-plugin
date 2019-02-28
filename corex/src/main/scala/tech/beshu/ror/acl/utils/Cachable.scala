package tech.beshu.ror.acl.utils

import com.github.blemale.scaffeine.Scaffeine
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task

import scala.concurrent.duration.FiniteDuration
import scala.util.Success
import TaskOps._

class CacheableAction[K, V](ttl: FiniteDuration Refined Positive,
                                    action: K => Task[V])
  extends CacheableActionWithKeyMapping[K, K, V](ttl, action, identity)

class CacheableActionWithKeyMapping[K, K1, V](ttl: FiniteDuration Refined Positive,
                                                      action: K => Task[V],
                                                      keyMap: K => K1) {

  private val cache = Scaffeine()
    .expireAfterWrite(ttl.value)
    .build[K, V]

  def call(key: K): Task[V] = {
    cache.getIfPresent(key) match {
      case Some(value) =>
        Task.now(value)
      case None =>
        action(key)
          .andThen {
            case Success(value) => cache.put(key, value)
          }
    }
  }

}