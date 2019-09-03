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

import com.github.blemale.scaffeine.Scaffeine
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.utils.TaskOps._

import scala.concurrent.ExecutionContext._
import scala.concurrent.duration.FiniteDuration
import scala.util.Success

class CacheableAction[K, V](ttl: FiniteDuration Refined Positive,
                            action: K => Task[V])
  extends CacheableActionWithKeyMapping[K, K, V](ttl, action, identity)

class CacheableActionWithKeyMapping[K, K1, V](ttl: FiniteDuration Refined Positive,
                                              action: K => Task[V],
                                              keyMap: K => K1) {

  private val cache = Scaffeine()
    .executor(global)
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