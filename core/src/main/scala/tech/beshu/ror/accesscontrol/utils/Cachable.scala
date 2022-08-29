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
import tech.beshu.ror.utils.TaskOps._

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, TimeoutException}
import scala.concurrent.ExecutionContext._
import scala.concurrent.duration.FiniteDuration
import scala.util.Success

class CacheableAction[K, V](ttl: FiniteDuration Refined Positive,
                            action: K => Task[V])
  extends CacheableActionWithKeyMapping[K, K, V](ttl, action, identity)

class CacheableActionWithKeyMapping[K, K1, V](ttl: FiniteDuration Refined Positive,
                                              action: K => Task[V],
                                              keyMap: K => K1) extends Logging {

  private val keySemaphoresMap = new ConcurrentHashMap[K1, Semaphore[Task]]()

  private val cache = Scaffeine()
    .executor(global)
    .expireAfterWrite(ttl.value)
    .removalListener(onRemoveHook)
    .build[K1, V]

  def call(key: K, requestTimeout: FiniteDuration Refined Positive): Task[V] = {
    val correlationId = UUID.randomUUID().toString
    val mappedKey = keyMap(key)
    val ll = for {
      semaphore <- semaphoreOf(mappedKey)
      _ <- Task.delay(logger.debug(s"[${correlationId}] WAITING action for $key"))
      cachedValue <- semaphore.withPermit {
        for {
          _ <- Task.delay(logger.debug(s"[${correlationId}] STARTING action for $key"))
          res <- getFromCacheOrRunAction(key, mappedKey)
          _ <- Task.delay(logger.debug(s"[${correlationId}] Finishing action for $key"))
        } yield res
      }
    } yield cachedValue
    ll.timeoutTo(
      requestTimeout.value,
      Task.delay {
        logger.debug(s"[${correlationId}] Cancelling action for $key")
        throw new TimeoutException("Action cancelled")
      }
    )
  }

  // todo: refactoring needed
  def call(key: K): Task[V] = {
    val correlationId = UUID.randomUUID().toString
    val mappedKey = keyMap(key)
    for {
      semaphore <- semaphoreOf(mappedKey)
      _ <- Task.delay(logger.debug(s"[${correlationId}] WAITING action for $key"))
      cachedValue <- semaphore.withPermit {
        for {
          _ <- Task.delay(logger.debug(s"[${correlationId}] STARTING action for $key"))
          res <- getFromCacheOrRunAction(key, mappedKey)
          _ <- Task.delay(logger.debug(s"[${correlationId}] Finishing action for $key"))
        } yield res
      }
    } yield cachedValue
  }

  private def getFromCacheOrRunAction(key: K, mappedKey: K1): Task[V] = {
    for {
      cachedValue <- Task.delay(cache.getIfPresent(mappedKey))
      result <- cachedValue match {
        case Some(value) => Task.now(value)
        case None =>
          action(key)
            .andThen {
              case Success(value) => cache.put(mappedKey, value)
            }
      }
    } yield result
  }

  private def onRemoveHook(mappedKay: K1, value: V, cause: RemovalCause): Unit = {
    keySemaphoresMap.remove(mappedKay)
  }

  private def semaphoreOf(key: K1) = for {
    newSemaphore <- Semaphore[Task](1)
    usedSemaphore = Option(keySemaphoresMap.putIfAbsent(key, newSemaphore)).getOrElse(newSemaphore)
  } yield usedSemaphore
}