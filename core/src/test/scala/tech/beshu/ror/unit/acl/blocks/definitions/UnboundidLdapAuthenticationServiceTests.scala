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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, BeforeAndAfterAll, BeforeAndAfterEach, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute.CustomAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{Dn, LdapAuthenticationService}
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.containers.LdapContainer

import java.time.Clock
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag

class UnboundidLdapAuthenticationServiceTests
  extends AnyWordSpec
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ForAllTestContainer
    with Inside {

  private val ldapContainer = LdapContainer.create("LDAP3", "test_example.ldif")
  private val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override val container: Container = MultipleContainers(ldapContainer)

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close()
      .runSyncUnsafe()
  }


  "An LdapAuthenticationService" should {
    "have method to authenticate" which {
      "returns true" when {
        "user exists in LDAP and its credentials are correct" in {
          createSimpleAuthenticationService().assertSuccessfulAuthentication
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
            .authenticate(User.Id("morgan"), PlainTextSecret("invalid_secret"))
            .runSyncUnsafe() should be(false)
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
        Name("LDAP3"),
        ldapConnectionPoolProvider,
        LdapConnectionConfig(
          ConnectionMethod.SingleServer(
            LdapHost
              .from(s"ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}")
              .get
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

}
