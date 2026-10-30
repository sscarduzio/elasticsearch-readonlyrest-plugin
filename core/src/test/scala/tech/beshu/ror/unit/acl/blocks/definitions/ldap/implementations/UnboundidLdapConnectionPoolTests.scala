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
import com.unboundid.ldap.sdk.*
import eu.timepit.refined.api.Refined
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPool
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.BindRequestUser

import scala.concurrent.duration.*
import scala.language.postfixOps

class UnboundidLdapConnectionPoolTests extends AnyWordSpec with BeforeAndAfterAll with Inside {

  private val baseDn = "dc=example,dc=com"

  private val server = {
    val config = new InMemoryDirectoryServerConfig(baseDn)
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
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
    pool
      .process(new SearchRequest(_, baseDn, SearchScope.BASE, "(objectClass=*)"), Refined.unsafeApply(5 seconds))
      // A bound for the test only: without it, a search that never completes blocks the suite.
      .runSyncUnsafe(30 seconds)
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

}
