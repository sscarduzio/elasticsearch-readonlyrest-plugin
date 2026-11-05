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

import com.unboundid.ldap.listener.interceptor.{InMemoryInterceptedSimpleBindRequest, InMemoryOperationInterceptor}
import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig, InMemoryListenerConfig}
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPException, ResultCode, ServerSet}
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError.{
  BindingTestError,
  UnexpectedConnectionError
}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.{
  BindRequestUser,
  ConnectionMethod,
  LdapHost
}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidOps.connectionWithin
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.{
  UnboundidLdapConnectionPool,
  UnboundidLdapConnectionPoolProvider
}
import tech.beshu.ror.utils.AccessControllerFrames.runsInsideDoPrivileged
import tech.beshu.ror.utils.CallerSchedulerSupport
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit, TimeoutException}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps
import scala.util.{Failure, Try}

class UnboundidLdapConnectionPoolProviderTests
    extends AnyWordSpec
    with BeforeAndAfterAll
    with Eventually
    with CallerSchedulerSupport {

  "An UnboundidLdapConnectionPoolProvider" should {
    "not block the threads of the calling scheduler while the binding test waits for the LDAP server" in {
      withSingleThreadScheduler { callerScheduler =>
        withPoolProvider { poolProvider =>
          val connecting = requestHold.whileHeld {
            val call = startOn(
              callerScheduler,
              UnboundidLdapConnectionPoolProvider.connectWithOptionalBindingTest(poolProvider, connectionConfig)
            )
            requestHold.awaitHeldRequests(1)
            runsOtherTask(callerScheduler) should be(true)
            call
          }
          resultOf(connecting) should matchPattern { case Right(_) => }
        }
      }
    }
    "not block the threads of the calling scheduler while the pool creation waits for the LDAP server" in {
      withSingleThreadScheduler { callerScheduler =>
        withPoolProvider { poolProvider =>
          val connecting = requestHold.whileHeld {
            val call = startOn(callerScheduler, poolProvider.connect(connectionConfig))
            requestHold.awaitHeldRequests(1)
            runsOtherTask(callerScheduler) should be(true)
            call
          }
          resultOf(connecting) shouldBe a[UnboundidLdapConnectionPool]
        }
      }
    }
    "retry a failed bind of the binding test on the same open connection" in {
      withPoolProvider { poolProvider =>
        val result = bindFailures.whileFailing(1) {
          UnboundidLdapConnectionPoolProvider
            .connectWithOptionalBindingTest(poolProvider, connectionConfig)
            .runSyncUnsafe(30 seconds)
        }
        result should matchPattern { case Right(_) => }
        bindFailures.rejectedBinds should be(1)
        // The first two binds are the rejected bind and its retry. The pool makes the next bind on its own connection.
        bindFailures.bindConnectionIds match {
          case rejectedBind :: retry :: _ => retry should be(rejectedBind)
          case connectionIds              => fail(s"Expected at least 2 binds, got: $connectionIds")
        }
      }
    }
    "report a bind of the binding test that the server rejects as a binding test error, with no retry" in {
      withPoolProvider { poolProvider =>
        val result = bindFailures.whileFailing(3, ResultCode.INVALID_CREDENTIALS) {
          UnboundidLdapConnectionPoolProvider
            .connectWithOptionalBindingTest(poolProvider, connectionConfig)
            .runSyncUnsafe(30 seconds)
        }
        result should be(Left(BindingTestError(connectionConfig.poolName)))
        bindFailures.rejectedBinds should be(1)
      }
    }
    "report a temporary bind failure that stays after all retries as an unexpected connection error" in {
      withPoolProvider { poolProvider =>
        val result = bindFailures.whileFailing(3, ResultCode.UNAVAILABLE) {
          UnboundidLdapConnectionPoolProvider
            .connectWithOptionalBindingTest(poolProvider, connectionConfig)
            .runSyncUnsafe(30 seconds)
        }
        result should matchPattern { case Left(UnexpectedConnectionError(_, _)) => }
        bindFailures.rejectedBinds should be(3)
      }
    }
    "give the use an open connection and close it before the use ends" in {
      // The caller scheduler has one thread. A close that the use does not wait for can run only after the check.
      withSingleThreadScheduler { callerScheduler =>
        val serverSet = new DelayedServerSet
        serverSet.release()
        val connectedInAndAfterUse = startOn(
          callerScheduler,
          serverSet
            .connectionWithin(5 seconds)
            .use(result => Task(result.map(_.isConnected)))
            .map(inUse => (inUse, serverSet.createdConnection.map(_.isConnected)))
        )
        resultOf(connectedInAndAfterUse) should be((Right(true), Some(false)))
      }
    }
    "close a connection that comes after the connection timeout" in {
      val serverSet = new DelayedServerSet
      val result = Try {
        serverSet
          .connectionWithin(100 millis)
          .use(_ => Task.unit)
          .runSyncUnsafe(10 seconds)
      }
      result should matchPattern { case Failure(_: TimeoutException) => }
      serverSet.release()
      eventually {
        serverSet.createdConnection.map(_.isConnected) should be(Some(false))
      }
    }
    "connect to the server set with privileges" in {
      val serverSet = new DelayedServerSet
      serverSet.release()
      serverSet
        .connectionWithin(5 seconds)
        .use(_ => Task.unit)
        .runSyncUnsafe(10 seconds)
      serverSet.calledWithPrivileges should be(true)
    }
    "not block the threads of the calling scheduler while it waits for a connection" in {
      withSingleThreadScheduler { callerScheduler =>
        val serverSet = new DelayedServerSet
        val connecting = startOn(
          callerScheduler,
          serverSet
            .connectionWithin(5 seconds)
            .use(result => Task.now(result.isRight))
        )
        try {
          serverSet.awaitCalled()
          runsOtherTask(callerScheduler) should be(true)
        } finally serverSet.release()
        resultOf(connecting) should be(true)
      }
    }
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(50, Millis)))

  private val requestHold = new RequestHold
  private val bindFailures = new BindFailures

  private val server = {
    val config = new InMemoryDirectoryServerConfig("dc=example,dc=com")
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
    config.addInMemoryOperationInterceptor(requestHold)
    config.addInMemoryOperationInterceptor(bindFailures)
    new InMemoryDirectoryServer(config)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    server.startListening()
  }

  override protected def afterAll(): Unit = {
    server.shutDown(true)
    super.afterAll()
  }

  // The provider binds when it tests the connection and when it creates the pool, because it must not ignore
  // connectivity problems. The request timeout is longer than the wait in `awaitHeldRequests`.
  private def connectionConfig = LdapConnectionConfig(
    poolName = LdapService.Name(NonEmptyString.unsafeFrom("ldap")),
    connectionMethod = ConnectionMethod.SingleServer(LdapHost.from(s"ldap://127.0.0.1:${server.getListenPort}").get),
    poolSize = positiveInt(1),
    connectionHealthCheckInterval = positiveFiniteDuration(120, TimeUnit.SECONDS),
    connectionMaxAge = positiveFiniteDuration(120, TimeUnit.SECONDS),
    connectionTimeout = positiveFiniteDuration(5, TimeUnit.SECONDS),
    requestTimeout = positiveFiniteDuration(30, TimeUnit.SECONDS),
    trustAllCerts = false,
    bindRequestUser = BindRequestUser.Anonymous,
    ignoreLdapConnectivityProblems = false
  )

  private def withPoolProvider(test: UnboundidLdapConnectionPoolProvider => Unit): Unit = {
    val poolProvider = new UnboundidLdapConnectionPoolProvider
    try test(poolProvider)
    finally poolProvider.close().runSyncUnsafe(30 seconds)
  }

  // `getConnection` connects to the test server only after the test calls `release`.
  private class DelayedServerSet extends ServerSet {
    private val called = new CountDownLatch(1)
    private val released = new CountDownLatch(1)
    private val connection = new AtomicReference(Option.empty[LDAPConnection])
    @volatile private var privileged = false

    def release(): Unit = released.countDown()

    def awaitCalled(): Unit = {
      if (!called.await(10, TimeUnit.SECONDS)) fail("The SDK does not ask the server set for a connection")
    }

    def createdConnection: Option[LDAPConnection] = connection.get()

    // Returns true when `getConnection` ran inside `AccessController.doPrivileged`.
    def calledWithPrivileges: Boolean = privileged

    override def getConnection: LDAPConnection = {
      privileged = runsInsideDoPrivileged()
      called.countDown()
      if (!released.await(10, TimeUnit.SECONDS)) {
        throw new LDAPException(ResultCode.TIMEOUT, "The test does not release the connection")
      }
      val created = new LDAPConnection("127.0.0.1", server.getListenPort)
      connection.set(Some(created))
      created
    }

  }

  // Inside `whileFailing`, the server rejects the given number of simple binds.
  private class BindFailures extends InMemoryOperationInterceptor {
    private val remaining = new AtomicInteger(0)
    private val rejected = new AtomicInteger(0)
    private val bindConnections = new ConcurrentLinkedQueue[Long]()
    @volatile private var resultCode = ResultCode.UNAVAILABLE

    def whileFailing[A](count: Int, result: ResultCode = ResultCode.UNAVAILABLE)(block: => A): A = {
      resultCode = result
      rejected.set(0)
      bindConnections.clear()
      remaining.set(count)
      try block
      finally remaining.set(0)
    }

    // The number of binds that the server rejected in the last `whileFailing`.
    def rejectedBinds: Int = rejected.get()

    // The connection IDs of all binds in the last `whileFailing`, in the order of the binds.
    def bindConnectionIds: List[Long] = bindConnections.asScala.toList

    override def processSimpleBindRequest(request: InMemoryInterceptedSimpleBindRequest): Unit = {
      bindConnections.add(request.getConnectionID)
      if (remaining.getAndUpdate(left => math.max(left - 1, 0)) > 0) {
        rejected.incrementAndGet()
        throw new LDAPException(resultCode, "The test rejects this bind")
      }
    }

  }

}
