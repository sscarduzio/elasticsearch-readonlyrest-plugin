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
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext.*

class AsyncCacheableAction2[K, V](ttl: PositiveFiniteDuration,
                                  action: K => Task[V]) {

  import CacheableActionCaffeineOps.*

  private val keySemaphoresMap = new ConcurrentHashMap[K, Semaphore[Task]]()

  private val cache: Cache[K, V] =
    Caffeine.newBuilder()
      .executor(global)
      .removalListener(onRemoveHook)
      .withOptionalTtl(Some(ttl))
      .build[K, V]()

  def call(key: K): Task[V] = Task.defer {
    Option(cache.getIfPresent(key)) match {
      case Some(value) => Task.now(value)
      case None =>
        semaphoreOf(key).flatMap { semaphore =>
          semaphore.withPermit {
            getFromCacheOrRunAction(key).uncancelable.asyncBoundary
          }
        }
    }
  }

  private def getFromCacheOrRunAction(key: K): Task[V] = Task.defer {
    Option(cache.getIfPresent(key)) match {
      case Some(value) =>
        Task.now(value)
      case None =>
        action(key).map { value =>
          cache.put(key, value)
          value
        }
    }
  }

  def invalidateAll(): Unit = {
    cache.invalidateAll()
    keySemaphoresMap.clear()
  }

  private def onRemoveHook(mappedKey: K,
                           @nowarn value: V,
                           @nowarn cause: RemovalCause): Unit = {
    keySemaphoresMap.remove(mappedKey)
  }

  private def semaphoreOf(key: K): Task[Semaphore[Task]] = {
    Option(keySemaphoresMap.get(key)) match {
      case Some(existing) => Task.now(existing)
      case None =>
        Semaphore[Task](1).map { newSemaphore =>
          Option(keySemaphoresMap.putIfAbsent(key, newSemaphore)).getOrElse(newSemaphore)
        }
    }
  }
}

class AsyncCacheableAction[K, V](ttl: Option[PositiveFiniteDuration],
                                 action: (K, RequestId) => Task[V])
  extends AsyncCacheableActionWithKeyMapping[K, K, V](ttl, action, identity[K]) {

  def this(action: (K, RequestId) => Task[V]) = this(None, action)

  def this(ttl: PositiveFiniteDuration, action: (K, RequestId) => Task[V]) = this(Some(ttl), action)
}

class AsyncCacheableActionWithKeyMapping[K, K1, V](ttl: Option[PositiveFiniteDuration],
                                                   action: (K, RequestId) => Task[V],
                                                   keyMap: K => K1) {

  def this(action: (K, RequestId) => Task[V], keyMap: K => K1) = this(None, action, keyMap)

  def this(ttl: PositiveFiniteDuration, action: (K, RequestId) => Task[V], keyMap: K => K1) = this(Some(ttl), action, keyMap)

  import CacheableActionCaffeineOps.*

  private val keySemaphoresMap = new ConcurrentHashMap[K1, Semaphore[Task]]()

  private val cache: Cache[K1, V] =
    Caffeine.newBuilder()
      .executor(global)
      .removalListener(onRemoveHook)
      .withOptionalTtl(ttl)
      .build[K1, V]()

  def call(key: K)(implicit requestId: RequestId): Task[V] = Task.defer {
    val mappedKey = keyMap(key)
    Option(cache.getIfPresent(mappedKey)) match {
      case Some(value) => Task.now(value)
      case None =>
        semaphoreOf(mappedKey).flatMap { semaphore =>
          semaphore.withPermit {
            getFromCacheOrRunAction(key, mappedKey).uncancelable.asyncBoundary
          }
        }
    }
  }

  private def getFromCacheOrRunAction(key: K, mappedKey: K1)(implicit requestId: RequestId): Task[V] = Task.defer {
    Option(cache.getIfPresent(mappedKey)) match {
      case Some(value) =>
        Task.now(value)
      case None =>
        action(key, requestId).map { value =>
          cache.put(mappedKey, value)
          value
        }
    }
  }

  def invalidateAll(): Unit = {
    cache.invalidateAll()
    keySemaphoresMap.clear()
  }

  private def onRemoveHook(mappedKey: K1,
                           @nowarn value: V,
                           @nowarn cause: RemovalCause): Unit = {
    keySemaphoresMap.remove(mappedKey)
  }

  private def semaphoreOf(key: K1): Task[Semaphore[Task]] = {
    Option(keySemaphoresMap.get(key)) match {
      case Some(existing) => Task.now(existing)
      case None =>
        Semaphore[Task](1).map { newSemaphore =>
          Option(keySemaphoresMap.putIfAbsent(key, newSemaphore)).getOrElse(newSemaphore)
        }
    }
  }
}

class AsyncCacheableActionWithTimeout[K, V](ttl: PositiveFiniteDuration,
                                            action: (K, RequestId) => Task[V])
  extends AsyncCacheableActionWithKeyMappingAndTimeout[K, K, V](ttl, action, identity[K])

class AsyncCacheableActionWithKeyMappingAndTimeout[K, K1, V](ttl: PositiveFiniteDuration,
                                                             action: (K, RequestId) => Task[V],
                                                             keyMap: K => K1)
  extends AsyncCacheableActionWithKeyMapping[K, K1, V](Some(ttl), action, keyMap) {

  def call(key: K, requestTimeout: PositiveFiniteDuration)
          (implicit requestId: RequestId): Task[V] = {
    call(key).timeout(requestTimeout.value)
  }
}

class SyncCacheableAction[K, V](ttl: Option[PositiveFiniteDuration],
                                action: (K, RequestId) => V)
  extends SyncCacheableActionWithKeyMapping[K, K, V](ttl, action, identity[K]) {

  def this(action: (K, RequestId) => V) = this(None, action)

  def this(ttl: PositiveFiniteDuration, action: (K, RequestId) => V) = this(Some(ttl), action)
}

class SyncCacheableActionWithKeyMapping[K, K1, V](ttl: Option[PositiveFiniteDuration],
                                                  action: (K, RequestId) => V,
                                                  keyMap: K => K1) {

  def this(action: (K, RequestId) => V, keyMap: K => K1) = this(None, action, keyMap)

  def this(ttl: PositiveFiniteDuration, action: (K, RequestId) => V, keyMap: K => K1) = this(Some(ttl), action, keyMap)

  import CacheableActionCaffeineOps.*

  private val cache: Cache[K1, V] =
    Caffeine.newBuilder()
      .withOptionalTtl(ttl)
      .build[K1, V]()

  def call(key: K)(implicit requestId: RequestId): V = {
    cache.get(keyMap(key), _ => action(key, requestId))
  }

  def invalidateAll(): Unit = {
    cache.invalidateAll()
  }
}

object CacheableActionCaffeineOps {

  extension [K, V](builder: Caffeine[K, V]) {
    def withOptionalTtl(ttl: Option[PositiveFiniteDuration]): Caffeine[K, V] = {
      ttl.foreach(t => builder.expireAfterWrite(t.value.toMillis, TimeUnit.MILLISECONDS))
      builder
    }
  }
}