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

import cats.data.EitherT
import com.dimafeng.testcontainers.{Container, ForAllTestContainer, MultipleContainers}
import eu.timepit.refined.api.Refined
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{Dn, LdapService}
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.TestsUtils.{ValueOrIllegalState, unsafeNes}
import tech.beshu.ror.utils.containers.LdapContainer
import tech.beshu.ror.utils.{SingletonLdapContainers, WithDummyRequestIdSupport}

import java.time.Clock
import scala.concurrent.duration.*
import scala.language.postfixOps

class UnboundidLdapAuthenticationServiceWhenUserIdAttributeIsUidTests extends UnboundidLdapAuthenticationServiceTests {
  override protected val userIdAttribute: UserIdAttribute = UserIdAttribute.CustomAttribute("uid")
  override protected val morganUserId: User.Id = User.Id("morgan")
}

class UnboundidLdapAuthenticationServiceWhenUserIdAttributeIsCnTests extends UnboundidLdapAuthenticationServiceTests {
  override protected val userIdAttribute: UserIdAttribute = UserIdAttribute.OptimizedCn
  override protected val morganUserId: User.Id = User.Id("Morgan Freeman")
}

abstract class UnboundidLdapAuthenticationServiceTests
  extends AnyWordSpec
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ForAllTestContainer
    with Inside
    with WithDummyRequestIdSupport {

  private val ldapContainer = LdapContainer.create("LDAP3", "test_example.ldif")
  private val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override val container: Container = MultipleContainers(ldapContainer)

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  "An LdapAuthenticationService" should {
    "have method to authenticate" which {
      "returns true" when {
        "user exists in LDAP and its credentials are correct" in {
          createSimpleAuthenticationService()
            .authenticate(morganUserId, PlainTextSecret("user1"))
            .runSyncUnsafe() should be(true)
        }
      }
      "returns false" when {
        "user doesn't exist in LDAP" in {
          createSimpleAuthenticationService()
            .authenticate(User.Id("unknown"), PlainTextSecret("user1"))
            .runSyncUnsafe() should be(false)
        }
        "user has invalid credentials" in {
          createSimpleAuthenticationService()
            .authenticate(morganUserId, PlainTextSecret("invalid_secret"))
            .runSyncUnsafe() should be(false)
        }
      }
    }
  }

  private def createSimpleAuthenticationService() = {
    implicit val clock: Clock = Clock.systemUTC()
    val ldapId = Name("ldap")
    val ldapConnectionConfig = createLdapConnectionConfig(ldapId)
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com"), userIdAttribute)
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

  private def createLdapConnectionConfig(poolName: LdapService.Name) = {
    LdapConnectionConfig(
      poolName = poolName,
      connectionMethod = ConnectionMethod.SingleServer(
        LdapHost
          .from(s"ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}")
          .get
      ),
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

  protected def userIdAttribute: UserIdAttribute

  protected def morganUserId: User.Id

}
