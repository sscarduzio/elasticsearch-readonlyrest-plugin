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
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPool
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.BindRequestUser
import tech.beshu.ror.accesscontrol.domain.PlainTextSecret
import tech.beshu.ror.utils.CallerSchedulerSupport

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

class UnboundidLdapConnectionPoolTests
    extends AnyWordSpec
    with BeforeAndAfterAll
    with Inside
    with CallerSchedulerSupport {

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
    "not block the threads of the calling scheduler while the LDAP server does not answer" when {
      def testWith(bindRequestUser: BindRequestUser): Unit = withSingleThreadScheduler { callerScheduler =>
        withPool(poolSize = 2, bindRequestUser = bindRequestUser) { pool =>
          val (searchCall, bindCall) = requestHold.whileHeld {
            val calls = (startSearch(pool, callerScheduler), startBind(pool, callerScheduler))
            requestHold.awaitHeldRequests(2)
            runsOtherTask(callerScheduler) should be(true)
            calls
          }
          inside(resultOf(searchCall)) { case Right(entries) =>
            entries.map(_.getDN) should be(List(baseDn))
          }
          resultOf(bindCall).getResultCode should be(ResultCode.SUCCESS)
        }
      }
      "the pool binds anonymously" in testWith(BindRequestUser.Anonymous)
      "the pool binds with a service account" in testWith(serviceAccount)
    }
    "run at most as many LDAP calls in parallel as the pool size" in {
      withSingleThreadScheduler { callerScheduler =>
        withPool(poolSize = 2) { pool =>
          val (searches, binds) = requestHold.whileHeld {
            val calls =
              (List.fill(3)(startSearch(pool, callerScheduler)), List.fill(2)(startBind(pool, callerScheduler)))
            requestHold.awaitHeldRequests(2)
            requestHold.countNewHeldRequests(during = 1 second) should be(0)
            runsOtherTask(callerScheduler) should be(true)
            calls
          }
          searches.map(resultOf).foreach { result =>
            inside(result) { case Right(entries) => entries.map(_.getDN) should be(List(baseDn)) }
          }
          binds.map(resultOf).foreach(_.getResultCode should be(ResultCode.SUCCESS))
        }
      }
    }
    "keep the permit until a cancelled LDAP call ends" in {
      withSingleThreadScheduler { callerScheduler =>
        withPool() { pool =>
          val second = requestHold.whileHeld {
            val first = startSearch(pool, callerScheduler)
            requestHold.awaitHeldRequests(1)
            first.cancel.runSyncUnsafe(5 seconds)
            val second = startSearch(pool, callerScheduler)
            requestHold.countNewHeldRequests(during = 1 second) should be(0)
            second
          }
          inside(resultOf(second)) { case Right(entries) => entries.map(_.getDN) should be(List(baseDn)) }
        }
      }
    }
    "not start the LDAP call of a request that is cancelled while it waits for a permit" in {
      withSingleThreadScheduler { callerScheduler =>
        withPool() { pool =>
          val searches = searchCount.countedDuring {
            val first = requestHold.whileHeld {
              val first = startSearch(pool, callerScheduler)
              requestHold.awaitHeldRequests(1)
              startSearch(pool, callerScheduler).cancel.runSyncUnsafe(5 seconds)
              first
            }
            inside(resultOf(first)) { case Right(entries) => entries.map(_.getDN) should be(List(baseDn)) }
            // The first search has given its permit back. A cancelled search that starts gets to the server in this time.
            Thread.sleep(1000)
          }
          searches should be(1)
        }
      }
    }
    "authenticate the pool connection as the service account again after a user bind" in {
      withPool(bindRequestUser = serviceAccount) { pool =>
        val bindDns = bindDnLog.recordedDuring {
          bind(pool, userDn, userPassword).getResultCode should be(ResultCode.SUCCESS)
        }
        bindDns should be(List(userDn, serviceDn))
      }
    }
    "not block the threads of the calling scheduler while the SDK waits for a health check to close the pool" in {
      withSingleThreadScheduler { callerScheduler =>
        withPoolInBackgroundCheck { (pool, backgroundCheck) =>
          val closing = startOn(callerScheduler, pool.close())
          runsOtherTask(callerScheduler) should be(true)
          backgroundCheck.release()
          resultOf(closing) should be(())
        }
      }
    }
  }

  private val baseDn = "dc=example,dc=com"
  private val serviceDn = s"cn=service,$baseDn"
  private val servicePassword = "service-password"
  private val userDn = s"cn=user,$baseDn"
  private val userPassword = "user-password"

  private lazy val serviceAccount = BindRequestUser.CustomUser(
    Dn(NonEmptyString.unsafeFrom(serviceDn)),
    PlainTextSecret(NonEmptyString.unsafeFrom(servicePassword))
  )

  private val requestHold = new RequestHold
  private val bindDnLog = new BindDnLog
  private val searchCount = new SearchCount

  private val server = {
    val config = new InMemoryDirectoryServerConfig(baseDn)
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
    config.addInMemoryOperationInterceptor(requestHold)
    config.addInMemoryOperationInterceptor(bindDnLog)
    config.addInMemoryOperationInterceptor(searchCount)
    new InMemoryDirectoryServer(config)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    server.startListening()
    server.add(s"dn: $baseDn", "objectClass: top", "objectClass: domain", "dc: example")
    server.add(
      s"dn: $serviceDn",
      "objectClass: person",
      "cn: service",
      "sn: service",
      s"userPassword: $servicePassword"
    )
    server.add(s"dn: $userDn", "objectClass: person", "cn: user", "sn: user", s"userPassword: $userPassword")
  }

  override protected def afterAll(): Unit = {
    server.shutDown(true)
    super.afterAll()
  }

  private def withPool(
      healthCheck: LDAPConnectionPoolHealthCheck = new LDAPConnectionPoolHealthCheck,
      poolSize: Int = 1,
      bindRequestUser: BindRequestUser = BindRequestUser.Anonymous
  )(test: UnboundidLdapConnectionPool => Unit): Unit = {
    val connection = bindRequestUser match {
      case BindRequestUser.Anonymous                => new LDAPConnection("localhost", server.getListenPort)
      case BindRequestUser.CustomUser(dn, password) =>
        new LDAPConnection("localhost", server.getListenPort, dn.value.value, password.value.value)
    }
    val connectionPool = new LDAPConnectionPool(connection, 1, poolSize)
    connectionPool.setHealthCheck(healthCheck)
    val pool = UnboundidLdapConnectionPool.create(connectionPool, bindRequestUser).runSyncUnsafe()
    try test(pool)
    finally connectionPool.close()
  }

  // `withPool` cannot use this check: its `finally` closes the SDK pool, and the close waits for the check.
  private def withPoolInBackgroundCheck(test: (UnboundidLdapConnectionPool, BlockedBackgroundCheck) => Unit): Unit = {
    val backgroundCheck = new BlockedBackgroundCheck
    val connectionPool = new LDAPConnectionPool(new LDAPConnection("localhost", server.getListenPort), 1)
    connectionPool.setHealthCheck(backgroundCheck)
    connectionPool.setHealthCheckIntervalMillis(10)
    val pool = UnboundidLdapConnectionPool.create(connectionPool, BindRequestUser.Anonymous).runSyncUnsafe()
    try {
      backgroundCheck.awaitStarted()
      test(pool, backgroundCheck)
    } finally {
      backgroundCheck.release()
      connectionPool.close()
    }
  }

  private def search(pool: UnboundidLdapConnectionPool) = {
    searchTask(pool)
      // A bound for the test only: without it, a search that never completes blocks the suite.
      .runSyncUnsafe(30 seconds)
  }

  private def searchTask(pool: UnboundidLdapConnectionPool, timeout: FiniteDuration = 5 seconds) = {
    pool.process(new SearchRequest(_, baseDn, SearchScope.BASE, "(objectClass=*)"), Refined.unsafeApply(timeout))
  }

  // The search timeout is longer than the wait in `awaitHeldRequests`, so a held search does not time out first.
  private def startSearch(pool: UnboundidLdapConnectionPool, scheduler: Scheduler) = {
    startOn(scheduler, searchTask(pool, 30 seconds))
  }

  private def startBind(pool: UnboundidLdapConnectionPool, scheduler: Scheduler) = {
    startOn(scheduler, pool.asyncBind(new SimpleBindRequest()))
  }

  private def bind(pool: UnboundidLdapConnectionPool, dn: String, password: String) = {
    pool.asyncBind(new SimpleBindRequest(dn, password)).runSyncUnsafe(30 seconds)
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

  // The background check of the pool waits until the test calls `release`.
  private class BlockedBackgroundCheck extends LDAPConnectionPoolHealthCheck {
    private val started = new CountDownLatch(1)
    private val released = new CountDownLatch(1)

    def awaitStarted(): Unit = {
      if (!started.await(10, TimeUnit.SECONDS)) fail("The pool does not run its background check")
    }

    def release(): Unit = released.countDown()

    // The SDK close can interrupt the threads of the pool, so the wait ignores interrupts.
    override def ensureConnectionValidForContinuedUse(connection: LDAPConnection): Unit = {
      started.countDown()
      while (released.getCount > 0) {
        try released.await()
        catch { case _: InterruptedException => () }
      }
    }

  }

  // `recordedDuring` returns the DNs of the simple binds that the server gets while its block runs.
  private class BindDnLog extends InMemoryOperationInterceptor {
    private val bindDns = new ConcurrentLinkedQueue[String]

    def recordedDuring(block: => Unit): List[String] = {
      bindDns.clear()
      block
      bindDns.asScala.toList
    }

    override def processSimpleBindRequest(request: InMemoryInterceptedSimpleBindRequest): Unit = {
      bindDns.add(request.getRequest.getBindDN)
    }

  }

  // `countedDuring` returns the number of searches that the server gets while its block runs.
  private class SearchCount extends InMemoryOperationInterceptor {
    private val searches = new AtomicInteger(0)

    def countedDuring(block: => Unit): Int = {
      searches.set(0)
      block
      searches.get()
    }

    override def processSearchRequest(request: InMemoryInterceptedSearchRequest): Unit = {
      searches.incrementAndGet()
    }

  }

}
