package tech.beshu.ror.unit.utils

import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import monix.eval.Task
import org.scalatest.Matchers._
import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.refined._
import tech.beshu.ror.accesscontrol.utils.CacheableActionWithKeyMapping

import scala.concurrent.duration._
import scala.language.postfixOps
import monix.execution.Scheduler.Implicits.global
import org.scalatest.exceptions.TestFailedException

class CacheableActionWithKeyMappingTests extends WordSpec with MockFactory {

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
        _ <- Task.gatherUnordered(
          List.fill(100)(cache.call("key1"))
        )
      } yield ()

      result.runSyncUnsafe()
    }
  }

  private def createTestCacheInstance(dataProvider: ExternalDataProvider,
                                      ttl: FiniteDuration = 10 seconds) = {
    new CacheableActionWithKeyMapping[String, String, Int](
      refineV[Positive](ttl).right.get,
      dataProvider.getBy,
      identity
    )
  }

  trait ExternalDataProvider {
    def getBy(key: String): Task[Int]
  }
}
