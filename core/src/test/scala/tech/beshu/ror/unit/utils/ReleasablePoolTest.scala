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

import cats.implicits._
import cats.{Id, Monad}
import monix.eval.Task
import monix.execution.atomic.{AtomicBoolean, AtomicInt}
import monix.execution.schedulers.TestScheduler
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.utils.ReleseablePool
import tech.beshu.ror.unit.utils.ReleasablePoolTest.Counter
import tech.beshu.ror.utils.TestsUtils.unsafeNes

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

private final class ReleasablePoolTaskTest extends ReleasablePoolTest[Task] {
  implicit val scheduler: TestScheduler= TestScheduler()
  override protected def acquire(counter: Counter): Task[counter.ReleasableResource] = Task.evalAsync(counter.create)
  override protected def release(counter: Counter)(resource: counter.ReleasableResource): Task[Unit] = Task.evalAsync(resource.close)
  override protected def await[A](a: Task[A]): A = {
    val t = a.executeAsync.runToFuture
    scheduler.tick()
    Await.result(t, 2 second)
  }
}

private final class ReleasablePoolIdTest extends ReleasablePoolTest[Id] {
  override protected def acquire(counter: Counter): Id[counter.ReleasableResource] = counter.create
  override protected def release(counter: Counter)(resource: counter.ReleasableResource): Id[Unit] = resource.close
  override protected def await[A](a: Id[A]): A = a
}

private sealed abstract class ReleasablePoolTest[M[_] : Monad] extends AnyWordSpec {

  protected def acquire(counter: Counter):M[counter.ReleasableResource]
  protected def release(counter: Counter)(resource:counter.ReleasableResource):M[Unit]
  protected def await[A](a:M[A]):A

  "releasable pool" when {
    "resource is gotten" should {
      "create new resource" in {
        val counter = new Counter
        val releasablePool: ReleseablePool[M, counter.ReleasableResource, Unit] = createReleaseablePool(counter)
        val resource = await(releasablePool.get(())).toOption.get
        resource.isClosed shouldBe false
        counter.count shouldBe 1
      }
      "resource can be closed"in {
        val counter = new Counter
        val releasablePool: ReleseablePool[M, counter.ReleasableResource, Unit] = createReleaseablePool(counter)
        val resource = await(releasablePool.get(())).toOption.get
        resource.isClosed shouldBe false
        counter.count shouldBe 1
        resource.close
        resource.isClosed shouldBe true
        counter.count shouldBe 0
      }
      "close on pool closes resource"in {
        val counter = new Counter
        val releasablePool: ReleseablePool[M, counter.ReleasableResource, Unit] = createReleaseablePool(counter)
        val resource = await(releasablePool.get(())).toOption.get
        resource.isClosed shouldBe false
        counter.count shouldBe 1
        await(releasablePool.close)
        resource.isClosed shouldBe true
        counter.count shouldBe 0
      }
      "close on pool closes all resources"in {
        val counter = new Counter
        val releasablePool: ReleseablePool[M, counter.ReleasableResource, Unit] = createReleaseablePool(counter)
        val resource1 = await(releasablePool.get(())).toOption.get
        val resource2 = await(releasablePool.get(())).toOption.get
        counter.count shouldBe 2
        await(releasablePool.close)
        resource1.isClosed shouldBe true
        resource2.isClosed shouldBe true
        counter.count shouldBe 0
      }
      "close 100 resources for 100 opened resources"in {
        val counter = new Counter
        val releasablePool: ReleseablePool[M, counter.ReleasableResource, Unit] = createReleaseablePool(counter)
        val resuurcesF = (0 until 100).map(_ => releasablePool.get(())).toList.sequence
        await(resuurcesF)
        counter.count shouldEqual 100
        await(releasablePool.close)
        counter.count shouldBe 0
      }
    }
  }

  private def createReleaseablePool(counter: Counter): ReleseablePool[M, counter.ReleasableResource, Unit] = {

    def acquireR(counter: Counter)(unit: Unit): M[counter.ReleasableResource] = acquire(counter)

    def releaseR(resource: counter.ReleasableResource): M[Unit] = release(counter)(resource)

    new ReleseablePool[M, counter.ReleasableResource, Unit](acquireR(counter)(_))(releaseR)

  }
}

object ReleasablePoolTest {
  final class Counter {
    private val atom = AtomicInt(0)
    def count: Int = atom.get()

    def create: ReleasableResource =
      atom.transformAndExtract(c => (new ReleasableResource, c + 1))

    final class ReleasableResource() {
      private val isOpen = AtomicBoolean(true)

      def close: Unit = isOpen.transform{
        case false => false
        case true =>
          atom.decrement()
          false
      }

      def isClosed: Boolean = !isOpen.get()
    }
  }
}
