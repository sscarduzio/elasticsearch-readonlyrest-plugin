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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.{
  BindRequestUser,
  ConnectionMethod,
  LdapHost
}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.{
  UnboundidLdapConnectionPool,
  UnboundidLdapConnectionPoolProvider
}
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import scala.concurrent.duration.*
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
      }
    }
    "give the use an open connection and close it after the use" in {
      val serverSet = new DelayedServerSet
      serverSet.release()
      val connectedInUse = UnboundidLdapConnectionPoolProvider
        .connectionWithin(serverSet, 5 seconds)
        .use(result => Task(result.map(_.isConnected)))
        .runSyncUnsafe(10 seconds)
      connectedInUse should be(Right(true))
      eventually {
        serverSet.createdConnection.map(_.isConnected) should be(Some(false))
      }
    }
    "close a connection that comes after the connection timeout" in {
      val serverSet = new DelayedServerSet
      val result = Try {
        UnboundidLdapConnectionPoolProvider
          .connectionWithin(serverSet, 100 millis)
          .use(_ => Task.unit)
          .runSyncUnsafe(10 seconds)
      }
      result should matchPattern { case Failure(_: TimeoutException) => }
      serverSet.release()
      eventually {
        serverSet.createdConnection.map(_.isConnected) should be(Some(false))
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
    private val released = new CountDownLatch(1)
    private val connection = new AtomicReference(Option.empty[LDAPConnection])

    def release(): Unit = released.countDown()

    def createdConnection: Option[LDAPConnection] = connection.get()

    override def getConnection: LDAPConnection = {
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

    def whileFailing[A](count: Int)(block: => A): A = {
      remaining.set(count)
      try block
      finally remaining.set(0)
    }

    override def processSimpleBindRequest(request: InMemoryInterceptedSimpleBindRequest): Unit = {
      if (remaining.getAndUpdate(left => math.max(left - 1, 0)) > 0) {
        throw new LDAPException(ResultCode.UNAVAILABLE, "The test rejects this bind")
      }
    }

  }

}
