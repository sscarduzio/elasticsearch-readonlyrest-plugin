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
package tech.beshu.ror.accesscontrol.utils

import com.github.benmanes.caffeine.cache.{Cache, Caffeine, RemovalCause}
import monix.catnap.Semaphore
import monix.eval.Task
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext.*

class CacheableAction[K, V](ttl: PositiveFiniteDuration,
                            action: (K, RequestId) => Task[V])
  extends CacheableActionWithKeyMapping[K, K, V](ttl, action, identity)

class CacheableActionWithKeyMapping[K, K1, V](ttl: PositiveFiniteDuration,
                                              action: (K, RequestId) => Task[V],
                                              keyMap: K => K1) extends RequestIdAwareLogging {

  private val keySemaphoresMap = new ConcurrentHashMap[K1, Semaphore[Task]]()

  private val cache: Cache[K1, V] =
    Caffeine.newBuilder()
      .executor(global)
      .expireAfterWrite(ttl.value.toMillis, TimeUnit.MILLISECONDS)
      .removalListener(onRemoveHook)
      .build[K1, V]()

  def call(key: K, requestTimeout: PositiveFiniteDuration)
          (implicit requestId: RequestId): Task[V] = {
    call(key).timeout(requestTimeout.value)
  }

  def call(key: K)(implicit requestId: RequestId): Task[V] = {
    val mappedKey = keyMap(key)
    for {
      semaphore <- semaphoreOf(mappedKey)
      cachedValue <- semaphore.withPermit {
        getFromCacheOrRunAction(key, mappedKey).uncancelable.asyncBoundary
      }
    } yield cachedValue
  }

  private def getFromCacheOrRunAction(key: K, mappedKey: K1)(implicit requestId: RequestId): Task[V] = {
    for {
      cachedValue <- Task.delay(Option(cache.getIfPresent(mappedKey)))
      result <- cachedValue match {
        case Some(value) =>
          Task.now(value)
        case None =>
          action(key, requestId)
            .flatMap { value =>
              Task
                .delay { cache.put(mappedKey, value) }
                .map(_ => value)
            }
      }
    } yield result
  }

  private def onRemoveHook(mappedKey: K1,
                           @nowarn value: V,
                           @nowarn cause: RemovalCause): Unit = {
    keySemaphoresMap.remove(mappedKey)
  }

  private def semaphoreOf(key: K1) = for {
    newSemaphore <- Semaphore[Task](1)
    usedSemaphore = Option(keySemaphoresMap.putIfAbsent(key, newSemaphore)).getOrElse(newSemaphore)
  } yield usedSemaphore
}