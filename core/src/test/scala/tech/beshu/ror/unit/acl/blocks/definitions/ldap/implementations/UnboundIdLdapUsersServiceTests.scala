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
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{Dn, LdapService, LdapUser}
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.utils.TestsUtils.ValueOrIllegalState
import tech.beshu.ror.utils.containers.LdapContainer
import tech.beshu.ror.utils.{SingletonLdapContainers, WithDummyRequestIdSupport}
import tech.beshu.ror.utils.TestsUtils.unsafeNes

import scala.concurrent.duration._
import scala.language.postfixOps
import tech.beshu.ror.utils.RefinedUtils.*

class UnboundIdLdapUsersServiceTests
  extends AnyFreeSpec
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ForAllTestContainer
    with Inside
    with WithDummyRequestIdSupport {

  private val ldapContainer = LdapContainer.create("LDAP4", "test_example.ldif")
  private val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override val container: Container = MultipleContainers(ldapContainer)

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  "An LdapUsersService should" - {
    "have method to get an LDAP user which" - {
      "just return the user is the CN optimization is enabled" in {
        val ldapUser = createSimpleLdapUsersService(UserIdAttribute.OptimizedCn)
          .ldapUserBy(User.Id("even-non-existing-user"))
          .runSyncUnsafe()

        ldapUser should be (Some(LdapUser(
          User.Id("even-non-existing-user"), Dn("cn=even-non-existing-user,ou=People,dc=example,dc=com"), confirmed = false
        )))
      }
      "searches for the user in LDAP and" - {
        "return the user if exists" in {
          val ldapUser = createSimpleLdapUsersService(UserIdAttribute.CustomAttribute("uid"))
            .ldapUserBy(User.Id("morgan"))
            .runSyncUnsafe()

          ldapUser should be(Some(LdapUser(
            User.Id("morgan"), Dn("cn=Morgan Freeman,ou=People,dc=example,dc=com"), confirmed = true
          )))
        }
        "return None if the user doesn't exist" in {
          val ldapUser = createSimpleLdapUsersService(UserIdAttribute.CustomAttribute("uid"))
            .ldapUserBy(User.Id("non-existing"))
            .runSyncUnsafe()

          ldapUser should be(None)
        }
      }
    }
  }

  private def createSimpleLdapUsersService(userIdAttribute: UserIdAttribute) = {
    val ldapId = Name("ldap")
    val ldapConnectionConfig = createLdapConnectionConfig(ldapId)
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com"), userIdAttribute)
      ))
    } yield usersService
    result.valueOrThrowIllegalState()
  }

  private def createLdapConnectionConfig(poolName: LdapService.Name): LdapConnectionConfig = {
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

}