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

import cats.data.*
import com.dimafeng.testcontainers.{Container, ForAllTestContainer, MultipleContainers}
import com.unboundid.ldap.sdk.LDAPSearchException
import eu.timepit.refined.api.Refined
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.{Assertion, BeforeAndAfterAll, BeforeAndAfterEach, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute.CustomAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{Dn, LdapAuthenticationService, LdapService}
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.ScalaOps.repeat
import tech.beshu.ror.utils.TestsUtils.{ValueOrIllegalState, unsafeNes}
import tech.beshu.ror.utils.containers.{LdapContainer, LdapSingleContainer, ToxiproxyContainer}
import tech.beshu.ror.utils.misc.OsUtils
import tech.beshu.ror.utils.{SingletonLdapContainers, WithDummyRequestIdSupport}

import java.time.Clock
import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Success, Try}

class UnboundidLdapUsersServiceNetworkRelatedTests
  extends AnyFreeSpec
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ForAllTestContainer
    with Inside
    with WithDummyRequestIdSupport {

  private lazy val ldap1ContainerWithToxiproxy = new ToxiproxyContainer(
    SingletonLdapContainers.ldap1,
    LdapContainer.port
  )
  private lazy val ldapContainerToStop = LdapSingleContainer.create("LDAP3", "test_example.ldif")
  private lazy val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override val container: Container = MultipleContainers(ldap1ContainerWithToxiproxy, ldapContainerToStop)

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    ldap1ContainerWithToxiproxy.enableNetwork()
    ldap1ContainerWithToxiproxy.disableNetworkTimeout()
  }

  // This test suite does not execute on Windows: there is currently no Windows version of ToxiproxyContainer
  if (!OsUtils.isWindows) {
    "An LdapAuthenticationService should" - {
      "allow to authenticate user" - {
        "after connection timeout and retry" in {
          val authenticationService = createSimpleAuthenticationService()
          authenticationService.assertSuccessfulAuthentication
          ldap1ContainerWithToxiproxy.enableNetworkTimeout()
          authenticationService.assertFailedAuthentication[LdapUnexpectedResult, LDAPSearchException]
          ldap1ContainerWithToxiproxy.disableNetworkTimeout()
          authenticationService.assertSuccessfulAuthentication
        }
        "after connection failure and retry" in {
          val authenticationService = createSimpleAuthenticationService()
          authenticationService.assertSuccessfulAuthentication
          ldap1ContainerWithToxiproxy.disableNetwork()
          authenticationService.assertFailedAuthentication[LDAPSearchException, LDAPSearchException]
          ldap1ContainerWithToxiproxy.enableNetwork()
          authenticationService.assertSuccessfulAuthentication
        }
      }
      "be able to work when" - {
        "Round robin HA method is configured when" - {
          "one of servers goes down" in {
            def assertMorganCanAuthenticate(service: UnboundidLdapAuthenticationService) = {
              service
                .authenticate(User.Id("morgan"), PlainTextSecret("user1"))
                .runSyncUnsafe() should be(true)
            }

            val service = createHaAuthenticationService()
            (for {
              _ <- repeat(maxRetries = 5, delay = 500 millis) {
                Task(assertMorganCanAuthenticate(service))
              }
              _ <- Task(ldapContainerToStop.stop())
              _ <- repeat(10, 500 millis) {
                Task(assertMorganCanAuthenticate(service))
              }
            } yield ()).runSyncUnsafe()
          }
        }
      }
    }
  }

  implicit class LdapAuthenticationServiceOps(authenticationService: LdapAuthenticationService) {
    def assertSuccessfulAuthentication: Assertion = {
      authenticationService
        .authenticate(User.Id("morgan"), PlainTextSecret("user1"))
        .runSyncUnsafe() should be(true)
    }

    def assertFailedAuthentication[T: ClassTag, S: ClassTag]: Assertion = {
      Try {
        authenticationService
          .authenticate(User.Id("morgan"), PlainTextSecret("user1"))
          .runSyncUnsafe()
      } match {
        case Failure(_: T) | Failure(_: S) =>
          succeed
        case Failure(_) | Success(_) =>
          fail(s"Expected either ${classTag[T].runtimeClass} or ${classTag[S].runtimeClass} to be thrown")
      }
    }
  }

  private def createSimpleAuthenticationService() = {
    implicit val clock: Clock = Clock.systemUTC()
    val ldapId = Name("my_ldap")
    val ldapConnectionConfig = createLdapConnectionConfig(
      poolName = ldapId,
      connectionMethod = ConnectionMethod.SingleServer(
        LdapHost.from(s"ldap://localhost:${ldap1ContainerWithToxiproxy.innerContainerMappedPort}").get
      )
    )
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com"), CustomAttribute("uid"))
      ))
      authenticationService <- EitherT(UnboundidLdapAuthenticationService
        .create(
          id = ldapId,
          ldapUsersService = usersService,
          poolProvider = ldapConnectionPoolProvider,
          connectionConfig = ldapConnectionConfig
        ))
    } yield authenticationService
    result.valueOrThrowIllegalState()
  }

  private def createHaAuthenticationService() = {
    implicit val clock: Clock = Clock.systemUTC()
    val ldapId = Name("my_ldap")
    val ldapConnectionConfig = createLdapConnectionConfig(
      poolName = ldapId,
      connectionMethod = ConnectionMethod.SeveralServers(
        NonEmptyList.of(
          LdapHost.from(s"ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}").get,
          LdapHost.from(s"ldap://${ldapContainerToStop.ldapHost}:${ldapContainerToStop.ldapPort}").get,
        ),
        HaMethod.RoundRobin
      )
    )
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com"), CustomAttribute("uid"))
      ))
      authenticationService <- EitherT(UnboundidLdapAuthenticationService
        .create(
          id = ldapId,
          ldapUsersService = usersService,
          poolProvider = ldapConnectionPoolProvider,
          connectionConfig = ldapConnectionConfig
        ))
    } yield authenticationService
    result.valueOrThrowIllegalState()
  }

  private def createLdapConnectionConfig(poolName: LdapService.Name,
                                         connectionMethod: ConnectionMethod) = {
    LdapConnectionConfig(
      poolName = poolName,
      connectionMethod = connectionMethod,
      poolSize = positiveInt(1),
      connectionTimeout = Refined.unsafeApply(5 seconds),
      requestTimeout = Refined.unsafeApply(5 seconds),
      trustAllCerts = false,
      bindRequestUser = BindRequestUser.CustomUser(
        Dn("cn=admin,dc=example,dc=com"),
        PlainTextSecret("password")
      ),
      ignoreLdapConnectivityProblems = false
    )
  }

}
