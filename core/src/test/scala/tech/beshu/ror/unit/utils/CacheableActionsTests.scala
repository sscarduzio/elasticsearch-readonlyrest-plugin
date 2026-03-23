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
package tech.beshu.ror.unit.utils

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.utils.*
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport

import scala.concurrent.duration.*
import scala.language.postfixOps

class CacheableActionsTests extends AnyWordSpec with MockFactory with WithDummyRequestIdSupport {

  "SyncCacheableAction" should {
    "call provider only once and then cache the result" in {
      val provider = mock[SyncDataProvider]
      (provider.getBy _).expects("key1").returning(1).once()

      val cache = new SyncCacheableAction[String, Int](
        action = (key, _) => provider.getBy(key)
      )

      cache.call("key1") should be (1)
      cache.call("key1") should be (1)
      cache.call("key1") should be (1)
    }
    "cache different keys independently" in {
      val provider = mock[SyncDataProvider]
      (provider.getBy _).expects("key1").returning(1).once()
      (provider.getBy _).expects("key2").returning(2).once()

      val cache = new SyncCacheableAction[String, Int](
        action = (key, _) => provider.getBy(key)
      )

      cache.call("key1") should be (1)
      cache.call("key2") should be (2)
      cache.call("key1") should be (1)
      cache.call("key2") should be (2)
    }
    "invalidate cached results" in {
      val provider = mock[SyncDataProvider]
      (provider.getBy _).expects("key1").returning(1).twice()

      val cache = new SyncCacheableAction[String, Int](
        action = (key, _) => provider.getBy(key)
      )

      cache.call("key1") should be (1)
      cache.invalidateAll()
      cache.call("key1") should be (1)
    }
    "invalidate cached result after TTL expiration" in {
      val provider = mock[SyncDataProvider]
      (provider.getBy _).expects("key1").returning(1).twice()

      val cache = new SyncCacheableAction[String, Int](
        ttl = (200 milliseconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key)
      )

      cache.call("key1") should be (1)
      Thread.sleep(500)
      cache.call("key1") should be (1)
    }
  }

  "SyncCacheableActionWithKeyMapping" should {
    "call provider only once for mapped key and then cache the result" in {
      val provider = mock[SyncDataProvider]
      (provider.getBy _).expects("key1").returning(1).once()

      val cache = new SyncCacheableActionWithKeyMapping[String, Int, Int](
        action = (key, _) => provider.getBy(key),
        keyMap = _.length
      )

      cache.call("key1") should be (1)
      cache.call("key1") should be (1)
    }
    "use mapped key for caching - different keys with same mapped key share cache" in {
      val provider = mock[SyncDataProvider]
      (provider.getBy _).expects("aaa").returning(1).once()

      val cache = new SyncCacheableActionWithKeyMapping[String, Int, Int](
        action = (key, _) => provider.getBy(key),
        keyMap = _.length
      )

      cache.call("aaa") should be (1)
      cache.call("bbb") should be (1)
    }
    "invalidate cached results" in {
      val provider = mock[SyncDataProvider]
      (provider.getBy _).expects("key1").returning(1).twice()

      val cache = new SyncCacheableActionWithKeyMapping[String, Int, Int](
        action = (key, _) => provider.getBy(key),
        keyMap = _.length
      )

      cache.call("key1") should be (1)
      cache.invalidateAll()
      cache.call("key1") should be (1)
    }
  }

  "AsyncCacheableAction" should {
    "call provider only once and then cache the result" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).once()

      val cache = new AsyncCacheableAction[String, Int](
        action = (key, _) => provider.getBy(key)
      )

      val result = for {
        r1 <- cache.call("key1")
        r2 <- cache.call("key1")
        r3 <- cache.call("key1")
      } yield {
        r1 should be (1)
        r2 should be (1)
        r3 should be (1)
      }

      result.runSyncUnsafe()
    }
    "cache different keys independently" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).once()
      (provider.getBy _).expects("key2").returning(Task.now(2)).once()

      val cache = new AsyncCacheableAction[String, Int](
        action = (key, _) => provider.getBy(key)
      )

      val result = for {
        r1 <- cache.call("key1")
        r2 <- cache.call("key2")
        r3 <- cache.call("key1")
        r4 <- cache.call("key2")
      } yield {
        r1 should be (1)
        r2 should be (2)
        r3 should be (1)
        r4 should be (2)
      }

      result.runSyncUnsafe()
    }
    "invalidate cached results" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).twice()

      val cache = new AsyncCacheableAction[String, Int](
        action = (key, _) => provider.getBy(key)
      )

      val result = for {
        r1 <- cache.call("key1")
        _ <- Task.delay(cache.invalidateAll())
        r2 <- cache.call("key1")
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
    "invalidate cached result after TTL expiration" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).twice()

      val cache = new AsyncCacheableAction[String, Int](
        ttl = (200 milliseconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key)
      )

      val result = for {
        r1 <- cache.call("key1")
        _ <- Task.sleep(500 milliseconds)
        r2 <- cache.call("key1")
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
    "sequence parallel calls for the same key" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _)
        .expects("key1")
        .returning(Task.sleep(1 second) >> Task.now(1))
        .once()

      val cache = new AsyncCacheableAction[String, Int](
        action = (key, _) => provider.getBy(key)
      )

      val result = for {
        results <- Task.parSequenceUnordered(
          List.fill(100)(cache.call("key1"))
        )
      } yield {
        results.foreach(_ should be (1))
      }

      result.runSyncUnsafe()
    }
  }

  "AsyncCacheableActionWithKeyMapping" should {
    "call provider only once for mapped key and then cache the result" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).once()

      val cache = new AsyncCacheableActionWithKeyMapping[String, Int, Int](
        action = (key, _) => provider.getBy(key),
        keyMap = _.length
      )

      val result = for {
        r1 <- cache.call("key1")
        r2 <- cache.call("key1")
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
    "use mapped key for caching - different keys with same mapped key share cache" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("aaa").returning(Task.now(1)).once()

      val cache = new AsyncCacheableActionWithKeyMapping[String, Int, Int](
        action = (key, _) => provider.getBy(key),
        keyMap = _.length
      )

      val result = for {
        r1 <- cache.call("aaa")
        r2 <- cache.call("bbb")
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
    "invalidate cached results" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).twice()

      val cache = new AsyncCacheableActionWithKeyMapping[String, Int, Int](
        action = (key, _) => provider.getBy(key),
        keyMap = _.length
      )

      val result = for {
        r1 <- cache.call("key1")
        _ <- Task.delay(cache.invalidateAll())
        r2 <- cache.call("key1")
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
  }

  "AsyncCacheableActionWithTimeout" should {
    "call provider only once and then cache the result" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).once()

      val cache = new AsyncCacheableActionWithTimeout[String, Int](
        ttl = (10 seconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key)
      )

      val timeout = (5 seconds).toRefinedPositiveUnsafe

      val result = for {
        r1 <- cache.call("key1", timeout)
        r2 <- cache.call("key1", timeout)
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
    "timeout when provider takes too long" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _)
        .expects("key1")
        .returning(Task.sleep(5 seconds) >> Task.now(1))
        .once()

      val cache = new AsyncCacheableActionWithTimeout[String, Int](
        ttl = (10 seconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key)
      )

      val shortTimeout = (200 milliseconds).toRefinedPositiveUnsafe

      val result = cache.call("key1", shortTimeout).attempt.map { r =>
        r.isLeft should be (true)
      }

      result.runSyncUnsafe()
    }
  }

  "AsyncCacheableActionWithKeyMappingAndTimeout" should {
    "call provider only once and then cache the result" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).once()

      val cache = new AsyncCacheableActionWithKeyMappingAndTimeout[String, String, Int](
        ttl = (10 seconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key),
        keyMap = identity
      )

      val timeout = (5 seconds).toRefinedPositiveUnsafe

      val result = for {
        r1 <- cache.call("key1", timeout)
        r2 <- cache.call("key1", timeout)
        r3 <- cache.call("key1", timeout)
      } yield {
        r1 should be (1)
        r2 should be (1)
        r3 should be (1)
      }

      result.runSyncUnsafe()
    }
    "invalidate cached result after TTL expiration" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("key1").returning(Task.now(1)).twice()

      val cache = new AsyncCacheableActionWithKeyMappingAndTimeout[String, String, Int](
        ttl = (200 milliseconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key),
        keyMap = identity
      )

      val timeout = (5 seconds).toRefinedPositiveUnsafe

      val result = for {
        r1 <- cache.call("key1", timeout)
        _ <- Task.sleep(500 milliseconds)
        r2 <- cache.call("key1", timeout)
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
    "sequence parallel calls for the same key" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _)
        .expects("key1")
        .returning(Task.sleep(1 second) >> Task.now(1))
        .once()

      val cache = new AsyncCacheableActionWithKeyMappingAndTimeout[String, String, Int](
        ttl = (10 seconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key),
        keyMap = identity
      )

      val timeout = (5 seconds).toRefinedPositiveUnsafe

      val result = for {
        results <- Task.parSequenceUnordered(
          List.fill(100)(cache.call("key1", timeout))
        )
      } yield {
        results.foreach(_ should be (1))
      }

      result.runSyncUnsafe()
    }
    "timeout when provider takes too long" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _)
        .expects("key1")
        .returning(Task.sleep(5 seconds) >> Task.now(1))
        .once()

      val cache = new AsyncCacheableActionWithKeyMappingAndTimeout[String, String, Int](
        ttl = (10 seconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key),
        keyMap = identity
      )

      val shortTimeout = (200 milliseconds).toRefinedPositiveUnsafe

      val result = cache.call("key1", shortTimeout).attempt.map { r =>
        r.isLeft should be (true)
      }

      result.runSyncUnsafe()
    }
    "use mapped key for caching" in {
      val provider = mock[AsyncDataProvider]
      (provider.getBy _).expects("aaa").returning(Task.now(1)).once()

      val cache = new AsyncCacheableActionWithKeyMappingAndTimeout[String, Int, Int](
        ttl = (10 seconds).toRefinedPositiveUnsafe,
        action = (key, _) => provider.getBy(key),
        keyMap = _.length
      )

      val timeout = (5 seconds).toRefinedPositiveUnsafe

      val result = for {
        r1 <- cache.call("aaa", timeout)
        r2 <- cache.call("bbb", timeout)
      } yield {
        r1 should be (1)
        r2 should be (1)
      }

      result.runSyncUnsafe()
    }
  }

  trait SyncDataProvider {
    def getBy(key: String): Int
  }

  trait AsyncDataProvider {
    def getBy(key: String): Task[Int]
  }
}
