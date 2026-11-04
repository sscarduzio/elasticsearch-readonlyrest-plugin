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
import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig, InMemoryListenerConfig}
import com.unboundid.ldap.sdk.*
import eu.timepit.refined.api.Refined
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPool
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.BindRequestUser

import java.util.concurrent.{CountDownLatch, Semaphore, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.language.postfixOps

class UnboundidLdapConnectionPoolTests extends AnyWordSpec with BeforeAndAfterAll with Inside {

  "An UnboundidLdapConnectionPool" should {
    "return the entries of a search" in {
      withPool() { pool =>
        inside(search(pool)) { case Right(entries) =>
          entries.map(_.getDN) should be(List(baseDn))
        }
      }
    }
    "return the error result of a search that the connection cannot send" in {
      // The SDK reports this failure only in the request ID, never to the search result listener.
      val connectionDrop = new ServerDropsConnectionAfterCheckout
      withPool(connectionDrop) { pool =>
        val result = search(pool)
        connectionDrop.droppedConnection should be(true)
        inside(result) { case Left(searchResult) =>
          searchResult.getResultCode should be(ResultCode.SERVER_DOWN)
        }
      }
    }
    "not block the threads of the calling scheduler while the LDAP server does not answer" in {
      val callerScheduler = Scheduler.singleThread("ldap-test-caller")
      try
        withPool() { pool =>
          val (searchResult, bindResult) = requestHold.whileHeld {
            // The calling scheduler has one thread for two LDAP calls.
            val search = searchTask(pool, 30 seconds).executeAsync.runToFuture(callerScheduler)
            val bind = pool.asyncBind(new SimpleBindRequest()).executeAsync.runToFuture(callerScheduler)
            requestHold.awaitHeldRequests(2)
            Task
              .eval("other task")
              .executeAsync
              .runSyncUnsafe(5 seconds)(callerScheduler, CanBlock.permit) should be("other task")
            (search, bind)
          }
          inside(Await.result(searchResult, 30 seconds)) { case Right(entries) =>
            entries.map(_.getDN) should be(List(baseDn))
          }
          Await.result(bindResult, 30 seconds).getResultCode should be(ResultCode.SUCCESS)
        }
      finally callerScheduler.shutdown()
    }
  }

  private val baseDn = "dc=example,dc=com"

  private val requestHold = new RequestHold

  private val server = {
    val config = new InMemoryDirectoryServerConfig(baseDn)
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
    config.addInMemoryOperationInterceptor(requestHold)
    new InMemoryDirectoryServer(config)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    server.startListening()
    server.add(s"dn: $baseDn", "objectClass: top", "objectClass: domain", "dc: example")
  }

  override protected def afterAll(): Unit = {
    server.shutDown(true)
    super.afterAll()
  }

  private def withPool(healthCheck: LDAPConnectionPoolHealthCheck = new LDAPConnectionPoolHealthCheck)(
      test: UnboundidLdapConnectionPool => Unit
  ): Unit = {
    val connectionPool = new LDAPConnectionPool(new LDAPConnection("localhost", server.getListenPort), 1)
    connectionPool.setHealthCheck(healthCheck)
    try test(new UnboundidLdapConnectionPool(connectionPool, BindRequestUser.Anonymous))
    finally connectionPool.close()
  }

  private def search(pool: UnboundidLdapConnectionPool) = {
    searchTask(pool)
      // A bound for the test only: without it, a search that never completes blocks the suite.
      .runSyncUnsafe(30 seconds)
  }

  private def searchTask(pool: UnboundidLdapConnectionPool, timeout: FiniteDuration = 5 seconds) = {
    pool.process(new SearchRequest(_, baseDn, SearchScope.BASE, "(objectClass=*)"), Refined.unsafeApply(timeout))
  }

  // The pool calls the check after it finds the connection open, and returns the connection when the check passes.
  // The server closes the connection at that moment, so the SDK cannot register or send the search request.
  private class ServerDropsConnectionAfterCheckout extends LDAPConnectionPoolHealthCheck {
    @volatile var droppedConnection: Boolean = false

    override def ensureConnectionValidForCheckout(connection: LDAPConnection): Unit = {
      if (!droppedConnection) {
        server.closeAllConnections(false)
        val deadline = System.nanoTime() + 10.seconds.toNanos
        while (connection.isConnected && System.nanoTime() < deadline) Thread.sleep(10)
        droppedConnection = !connection.isConnected
      }
    }

  }

  // Inside `whileHeld`, the server holds each search and simple bind and does not answer it.
  private class RequestHold extends InMemoryOperationInterceptor {
    private val heldRequests = new Semaphore(0)
    @volatile private var release = Option.empty[CountDownLatch]

    def whileHeld[A](block: => A): A = {
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

    override def processSearchRequest(request: InMemoryInterceptedSearchRequest): Unit = hold()

    override def processSimpleBindRequest(request: InMemoryInterceptedSimpleBindRequest): Unit = hold()

    private def hold(): Unit = release.foreach { latch =>
      heldRequests.release()
      latch.await()
    }

  }

}
