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
package tech.beshu.ror.unit.acl.blocks.definitions

import com.dimafeng.testcontainers.{Container, ForAllTestContainer, MultipleContainers}
import com.unboundid.ldap.sdk.LDAPSearchException
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.ExecutionRejectedException
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, BeforeAndAfterAll, BeforeAndAfterEach, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute.CustomAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{CircuitBreakerLdapAuthenticationServiceDecorator, Dn, LdapAuthenticationService}
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.containers.{LdapContainer, ToxiproxyContainer}

import java.time.Clock
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag

class LdapCircuitBreakerTests
  extends AnyWordSpec
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ForAllTestContainer
    with Inside
    with Eventually {

  private val ldap1ContainerWithToxiproxy = new ToxiproxyContainer(
    SingletonLdapContainers.ldap1,
    LdapContainer.defaults.ldap.port
  )
  private val ldapContainerToStop = LdapContainer.create("LDAP3", "test_example.ldif")
  private val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override val container: Container = MultipleContainers(ldap1ContainerWithToxiproxy, ldapContainerToStop)

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close()
      .runSyncUnsafe()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    ldap1ContainerWithToxiproxy.enableNetwork()
    ldap1ContainerWithToxiproxy.disableNetworkTimeout()
  }

  "An CircuitBreaker decorated LdapAuthenticationService" should {
    "close circuit breaker after 2 failed attempts" in {
      val authenticationService = createCircuitBreakerDecoratedSimpleAuthenticationService()
      eventually {
        authenticationService.assertSuccessfulAuthentication
        ldap1ContainerWithToxiproxy.disableNetwork()
        authenticationService.assertFailedAuthentication[LDAPSearchException]
        authenticationService.assertFailedAuthentication[LDAPSearchException]
        authenticationService.assertFailedAuthentication[ExecutionRejectedException]
        ldap1ContainerWithToxiproxy.enableNetwork()
        authenticationService.assertFailedAuthentication[ExecutionRejectedException]
      }
    }
    "close circuit breaker after 2 failed attempts, but open it later" in {
      val authenticationService = createCircuitBreakerDecoratedSimpleAuthenticationService()
      eventually {
        authenticationService.assertSuccessfulAuthentication
        ldap1ContainerWithToxiproxy.disableNetwork()
        authenticationService.assertFailedAuthentication[LDAPSearchException]
        ldap1ContainerWithToxiproxy.enableNetwork()
        ldap1ContainerWithToxiproxy.enableNetworkTimeout()
        authenticationService.assertFailedAuthentication[LDAPSearchException]
        authenticationService.assertFailedAuthentication[ExecutionRejectedException]
        ldap1ContainerWithToxiproxy.disableNetworkTimeout()
        Thread.sleep(750)
        authenticationService.assertSuccessfulAuthentication
        authenticationService.assertSuccessfulAuthentication
      }
    }
    "close circuit breaker after 2 failed attempts and keep it closed because of network issues" in {
      val authenticationService = createCircuitBreakerDecoratedSimpleAuthenticationService()
      eventually {
        authenticationService.assertSuccessfulAuthentication
        ldap1ContainerWithToxiproxy.disableNetwork()
        authenticationService.assertFailedAuthentication[LDAPSearchException]
        authenticationService.assertFailedAuthentication[LDAPSearchException]
        authenticationService.assertFailedAuthentication[ExecutionRejectedException]
        Thread.sleep(750)
        authenticationService.assertFailedAuthentication[LDAPSearchException]
        authenticationService.assertFailedAuthentication[ExecutionRejectedException]
      }
    }
  }

  private implicit class LdapAuthenticationServiceOps(authenticationService: LdapAuthenticationService) {
    def assertSuccessfulAuthentication: Assertion = {
      authenticationService
        .authenticate(User.Id("morgan"), PlainTextSecret("user1"))
        .runSyncUnsafe() should be(true)
    }

    def assertFailedAuthentication[T : ClassTag]: Assertion = {
      an [T] should be thrownBy authenticationService
        .authenticate(User.Id("morgan"), PlainTextSecret("user1"))
        .runSyncUnsafe()
    }
  }

  private def createSimpleAuthenticationService() = {
    implicit val clock: Clock = Clock.systemUTC()
    UnboundidLdapAuthenticationService
      .create(
        Name("my_ldap"),
        ldapConnectionPoolProvider,
        LdapConnectionConfig(
          ConnectionMethod.SingleServer(
            LdapHost.from(s"ldap://localhost:${ldap1ContainerWithToxiproxy.innerContainerMappedPort}").get
          ),
          poolSize = 1,
          connectionTimeout = Refined.unsafeApply(5 seconds),
          requestTimeout = Refined.unsafeApply(5 seconds),
          trustAllCerts = false,
          BindRequestUser.CustomUser(
            Dn("cn=admin,dc=example,dc=com"),
            PlainTextSecret("password")
          ),
          ignoreLdapConnectivityProblems = false,
        ),
        UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com"), CustomAttribute("uid"))
      )
      .runSyncUnsafe()
      .getOrElse(throw new IllegalStateException("LDAP connection problem"))
  }

  private def createCircuitBreakerDecoratedSimpleAuthenticationService() = {
    new CircuitBreakerLdapAuthenticationServiceDecorator(
      createSimpleAuthenticationService(),
      CircuitBreakerConfig(
        maxFailures = Refined.unsafeApply(2),
        resetDuration = Refined.unsafeApply(0.5 second))
    )
  }

}
