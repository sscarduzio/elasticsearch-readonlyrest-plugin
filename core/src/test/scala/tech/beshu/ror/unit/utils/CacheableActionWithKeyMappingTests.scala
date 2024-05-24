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
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.utils.CacheableActionWithKeyMapping
import tech.beshu.ror.utils.DurationOps._
import tech.beshu.ror.utils.WithDummyRequestIdSupport

import scala.concurrent.duration._
import scala.language.postfixOps
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class CacheableActionWithKeyMappingTests extends AnyWordSpec with MockFactory with WithDummyRequestIdSupport {

  "A cache" should {
    "call provider only once and then cache the result" in {
      val provider = mock[ExternalDataProvider]
      def mockKey1Call(): Unit = (provider.getBy _).expects("key1").returning(Task.now(1)).once()

      val cache = createTestCacheInstance(provider)

      val result = for {
        _ <- Task.now(mockKey1Call())
        _ <- cache.call("key1")
        _ <- cache.call("key1")
        _ <- cache.call("key1")
      } yield ()

      result.runSyncUnsafe()
    }
    "invalidate cached result after TTL expiration" in {
      val provider = mock[ExternalDataProvider]
      def mockKey1Call(): Unit = (provider.getBy _).expects("key1").returning(Task.now(1)).once()

      val cache = createTestCacheInstance(provider, ttl = 200 milliseconds)

      val result = for {
        _ <- Task.now(mockKey1Call())
        _ <- cache.call("key1")
        _ <- Task.sleep(500 milliseconds)
        _ <- Task {
          the [TestFailedException] thrownBy cache.call("key1").runSyncUnsafe()
        }
      } yield ()

      result.runSyncUnsafe()
    }
    "sequence parallel calls for the same key" in {
      val provider = mock[ExternalDataProvider]
      def mockKey1Call(): Unit = (provider.getBy _)
        .expects("key1")
        .returning(Task.sleep(1 second) >> Task.now(1))
        .once()

      val cache = createTestCacheInstance(provider)

      val result = for {
        _ <- Task.now(mockKey1Call())
        _ <- Task.parSequenceUnordered(
          List.fill(100)(cache.call("key1"))
        )
      } yield ()

      result.runSyncUnsafe()
    }
  }

  private def createTestCacheInstance(dataProvider: ExternalDataProvider,
                                      ttl: FiniteDuration = 10 seconds) = {
    new CacheableActionWithKeyMapping[String, String, Int](
      ttl.toRefinedPositiveUnsafe,
      (str, _) => dataProvider.getBy(str),
      identity
    )
  }

  trait ExternalDataProvider {
    def getBy(key: String): Task[Int]
  }
}
