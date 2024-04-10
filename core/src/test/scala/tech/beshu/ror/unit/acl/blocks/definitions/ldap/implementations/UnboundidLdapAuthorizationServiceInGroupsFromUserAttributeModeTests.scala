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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers._
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, LdapHost}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{Dn, LdapService}
import tech.beshu.ror.accesscontrol.domain.{Group, PlainTextSecret, User}
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueList
import tech.beshu.ror.utils.{SingletonLdapContainers, WithDummyRequestIdSupport}

import java.time.Clock
import scala.concurrent.duration._
import scala.language.postfixOps

class UnboundidLdapAuthorizationServiceInGroupsFromUserAttributeModeWhenUserIdAttributeIsUidTests
  extends UnboundidLdapAuthorizationServiceInGroupsFromUserAttributeModeTests {

  override protected val userIdAttribute: UserIdAttribute = UserIdAttribute.CustomAttribute("uid")
  override protected val jesusUserId: User.Id = User.Id("jesus")
  override protected val userSpeakerUserId: User.Id = User.Id("userSpeaker")
  override protected val spaghettiUserId: User.Id = User.Id("spaghetti")
}

class UnboundidLdapAuthorizationServiceInGroupsFromUserAttributeModeWhenUserIdAttributeIsCnTests
  extends UnboundidLdapAuthorizationServiceInGroupsFromUserAttributeModeTests {

  override protected val userIdAttribute: UserIdAttribute = UserIdAttribute.Cn
  override protected val jesusUserId: User.Id = User.Id("Jesus Christ")
  override protected val userSpeakerUserId: User.Id = User.Id("UserSpeaker (ext)")
  override protected val spaghettiUserId: User.Id = User.Id("Spaghetti Monster")
}

abstract class UnboundidLdapAuthorizationServiceInGroupsFromUserAttributeModeTests
  extends AnyWordSpec
    with BeforeAndAfterAll
    with Inside
    with Eventually
    with WithDummyRequestIdSupport {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  "An LdapAuthorizationService" should {
    "has method to provide user groups" which {
      "returns non empty set of groups" when {
        "user has groups" in {
          eventually {
            godAndRegionsLdapAuthorizationService.groupsOf(jesusUserId).runSyncUnsafe() should contain only(
              group("europe"), group("north america"), group("south america"), group("africa")
            )
          }
        }
      }
      "resolve nested groups properly" in {
        eventually {
          usersAndRolesLdapAuthorizationService.groupsOf(userSpeakerUserId).runSyncUnsafe() should be {
            UniqueList.of(group("developers"), group("speakers (external)"))
          }
        }
      }
      "returns empty set of groups" when {
        "user has no groups" in {
          eventually {
            godAndRegionsLdapAuthorizationService.groupsOf(spaghettiUserId).runSyncUnsafe() should be (UniqueList.empty[Group])
          }
        }
        "there is no user with given name" in {
          eventually {
            godAndRegionsLdapAuthorizationService.groupsOf(User.Id("unknown")).runSyncUnsafe() should be(UniqueList.empty[Group])
          }
        }
      }
    }
  }

  private def godAndRegionsLdapAuthorizationService = {
    implicit val clock: Clock = Clock.systemUTC()
    val ldapId = Name("ldap1")
    val ldapConnectionConfig = createLdapConnectionConfig(ldapId)
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=Gods,dc=example,dc=com"), userIdAttribute)
      ))
      authorizationService <- EitherT(
        UnboundidLdapGroupsFromUserEntryAuthorizationService
          .create(
            id = ldapId,
            ldapUsersService = usersService,
            poolProvider = ldapConnectionPoolProvider,
            connectionConfig = ldapConnectionConfig,
            groupsSearchFilter = GroupsFromUserEntry(
              Dn("ou=Regions,dc=example,dc=com"),
              GroupSearchFilter("(objectClass=*)"),
              GroupIdAttribute("cn"),
              GroupsFromUserAttribute("title"),
            ),
            nestedGroupsConfig = None
          )
      )
    } yield authorizationService
    result.valueOrThrowIllegalState()
  }

  private def usersAndRolesLdapAuthorizationService = {
    implicit val clock: Clock = Clock.systemUTC()
    val ldapId = Name("ldap2")
    val ldapConnectionConfig = createLdapConnectionConfig(ldapId)
    val result = for {
      usersService <- EitherT(UnboundidLdapUsersService.create(
        id = ldapId,
        poolProvider = ldapConnectionPoolProvider,
        connectionConfig = ldapConnectionConfig,
        userSearchFiler = UserSearchFilterConfig(Dn("ou=Users,dc=example,dc=com"), userIdAttribute)
      ))
      authorizationService <- EitherT(
        UnboundidLdapGroupsFromUserEntryAuthorizationService
          .create(
            id = ldapId,
            ldapUsersService = usersService,
            poolProvider = ldapConnectionPoolProvider,
            connectionConfig = ldapConnectionConfig,
            groupsSearchFilter = GroupsFromUserEntry(
              Dn("ou=Roles,dc=example,dc=com"),
              GroupSearchFilter("(objectClass=*)"),
              GroupIdAttribute("cn"),
              GroupsFromUserAttribute("memberOf"),
            ),
            nestedGroupsConfig = Some(NestedGroupsConfig(
              nestedLevels = 1,
              Dn("ou=Roles,dc=example,dc=com"),
              GroupSearchFilter("(objectClass=*)"),
              UniqueMemberAttribute("uniqueMember"),
              GroupIdAttribute("cn"),
            ))
          )
      )
    } yield authorizationService
    result.value
      .runSyncUnsafe()
      .getOrElse(throw new IllegalStateException("LDAP connection problem"))
  }

  private def createLdapConnectionConfig(poolName: LdapService.Name) = {
    LdapConnectionConfig(
      poolName = poolName,
      connectionMethod = ConnectionMethod.SingleServer(
        LdapHost
          .from(s"ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}")
          .get
      ),
      poolSize = 1,
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
  protected def jesusUserId: User.Id
  protected def userSpeakerUserId: User.Id
  protected def spaghettiUserId: User.Id
}
