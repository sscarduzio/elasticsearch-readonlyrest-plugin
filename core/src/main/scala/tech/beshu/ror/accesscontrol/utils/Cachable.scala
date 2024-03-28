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

import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.blemale.scaffeine.Scaffeine
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.catnap.Semaphore
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Corr

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext._
import scala.concurrent.duration.FiniteDuration

class CacheableAction[K, V](ttl: FiniteDuration Refined Positive,
                            action: (K, Corr) => Task[V])
  extends CacheableActionWithKeyMapping[K, K, V](ttl, action, identity)

class CacheableActionWithKeyMapping[K, K1, V](ttl: FiniteDuration Refined Positive,
                                              action: (K, Corr) => Task[V],
                                              keyMap: K => K1) extends Logging {

  private val keySemaphoresMap = new ConcurrentHashMap[K1, Semaphore[Task]]()

  private val cache = Scaffeine()
    .executor(global)
    .expireAfterWrite(ttl.value)
    .removalListener(onRemoveHook)
    .build[K1, V]()

  def call(key: K,
           requestTimeout: FiniteDuration Refined Positive)(implicit corr: Corr): Task[V] = {
    call(key).timeout(requestTimeout.value)
  }

  def call(key: K)(implicit corr: Corr): Task[V] = {
    val mappedKey = keyMap(key)
    for {
      _ <- Task.delay {
        logger.trace(s"[$corr] CACHEABLE ${this.hashCode()}: call key: $mappedKey - started")
      }
      semaphore <- semaphoreOf(mappedKey)
      _ <- Task.delay {
        logger.trace(s"[$corr] CACHEABLE ${this.hashCode()}: call key: $mappedKey - semaphore acquired")
      }
      cachedValue <- semaphore.withPermit {
        getFromCacheOrRunAction(key, mappedKey).uncancelable
      }
    } yield cachedValue
  }

  private def getFromCacheOrRunAction(key: K, mappedKey: K1)(implicit corr: Corr): Task[V] = {
    for {
      _ <- Task.delay {
        logger.trace(s"[$corr] CACHEABLE ${this.hashCode()}: call key: $mappedKey - permit acquired")
      }
      cachedValue <- Task.delay(cache.getIfPresent(mappedKey))
      result <- cachedValue match {
        case Some(value) =>
          logger.trace(s"[$corr] CACHEABLE ${this.hashCode()}: call key: $mappedKey - use cached value")
          Task.now(value)
        case None =>
          logger.trace(s"[$corr] CACHEABLE ${this.hashCode()}: call key: $mappedKey - call action")
          action(key, corr)
            .flatMap { value =>
              Task
                .delay {
                  logger.trace(s"[$corr] CACHEABLE ${this.hashCode()}: call key: $mappedKey - cache value: $value")
                  cache.put(mappedKey, value)
                }
                .map(_ => value)
            }
      }
      _ <- Task.delay {
        logger.trace(s"[$corr] CACHEABLE ${this.hashCode()}: call key: $mappedKey - done: value: $result")
      }
    } yield result
  }

  private def onRemoveHook(mappedKey: K1,
                           @nowarn("cat=unused") value: V,
                           @nowarn("cat=unused") cause: RemovalCause): Unit = {
    logger.trace(s"CACHEABLE ${this.hashCode()}: call key: $mappedKey - remove $cause")
    keySemaphoresMap.remove(mappedKey)
  }

  private def semaphoreOf(key: K1) = for {
    newSemaphore <- Semaphore[Task](1)
    usedSemaphore = Option(keySemaphoresMap.putIfAbsent(key, newSemaphore)).getOrElse(newSemaphore)
  } yield usedSemaphore
}