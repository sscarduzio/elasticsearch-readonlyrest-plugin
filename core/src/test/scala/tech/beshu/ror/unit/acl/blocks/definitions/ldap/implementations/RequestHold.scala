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
package tech.beshu.ror.unit.acl.blocks.definitions.ldap.implementations

import com.unboundid.ldap.listener.interceptor.{
  InMemoryInterceptedSearchRequest,
  InMemoryInterceptedSimpleBindRequest,
  InMemoryOperationInterceptor
}
import org.scalatest.Assertions.fail

import java.util.concurrent.{CountDownLatch, Semaphore, TimeUnit}
import scala.concurrent.duration.FiniteDuration

// Inside `whileHeld`, the server holds each search and simple bind and does not answer it.
final class RequestHold extends InMemoryOperationInterceptor {
  private val heldRequests = new Semaphore(0)
  @volatile private var release = Option.empty[CountDownLatch]

  def whileHeld[A](block: => A): A = {
    heldRequests.drainPermits()
    val latch = new CountDownLatch(1)
    release = Some(latch)
    try block
    finally {
      release = None
      latch.countDown()
    }
  }

  def awaitHeldRequests(count: Int): Unit = {
    if (!heldRequests.tryAcquire(count, 10, TimeUnit.SECONDS)) fail(s"The server does not hold $count requests")
  }

  // Waits for `during`, then returns the number of held requests that `awaitHeldRequests` did not count.
  def countNewHeldRequests(during: FiniteDuration): Int = {
    Thread.sleep(during.toMillis)
    heldRequests.availablePermits()
  }

  override def processSearchRequest(request: InMemoryInterceptedSearchRequest): Unit = hold()

  override def processSimpleBindRequest(request: InMemoryInterceptedSimpleBindRequest): Unit = hold()

  private def hold(): Unit = release.foreach { latch =>
    heldRequests.release()
    latch.await()
  }

}
