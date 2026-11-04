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

import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig, InMemoryListenerConfig}
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.*
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

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.language.postfixOps

class UnboundidLdapConnectionPoolProviderTests extends AnyWordSpec with BeforeAndAfterAll with CallerSchedulerSupport {

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
  }

  private val requestHold = new RequestHold

  private val server = {
    val config = new InMemoryDirectoryServerConfig("dc=example,dc=com")
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
    config.addInMemoryOperationInterceptor(requestHold)
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

}
