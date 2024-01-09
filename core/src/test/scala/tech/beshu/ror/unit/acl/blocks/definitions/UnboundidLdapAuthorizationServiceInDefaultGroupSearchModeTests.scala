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

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers._
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, LdapHost}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{DefaultGroupSearch, GroupIdAttribute, GroupSearchFilter, NestedGroupsConfig, UniqueMemberAttribute}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations._
import tech.beshu.ror.accesscontrol.domain.{Group, PlainTextSecret, User}
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils.group
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.concurrent.duration._
import scala.language.postfixOps

class UnboundidLdapAuthorizationServiceInDefaultGroupSearchModeTests
  extends AnyWordSpec
    with BeforeAndAfterAll
    with Inside
    with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close()
  }

  "An LdapAuthorizationService" should {
    "has method to provide user groups" which {
      "returns non empty set of groups" when {
        "user has groups" in {
          eventually {
            peopleAndGroupsLdapAuthorizationService.groupsOf(User.Id("morgan")).runSyncUnsafe() should be {
              UniqueList.of(group("groupAll"), group("group3"), group("group2"))
            }
          }
        }
      }
      "resolve nested groups properly" in {
        eventually {
          usersAndRolesLdapAuthorizationService.groupsOf(User.Id("userSpeaker")).runSyncUnsafe() should be {
            UniqueList.of(group("developers"), group("speakers (external)"))
          }
        }
      }
      "returns empty set of groups" when {
        "user has no groups" in {
          eventually {
            peopleAndGroupsLdapAuthorizationService.groupsOf(User.Id("devito")).runSyncUnsafe() should be(UniqueList.empty[Group])
          }
        }
        "there is no user with given name" in {
          eventually {
            peopleAndGroupsLdapAuthorizationService.groupsOf(User.Id("unknown")).runSyncUnsafe() should be(UniqueList.empty[Group])
          }
        }
      }
    }
  }

  private def peopleAndGroupsLdapAuthorizationService = {
    UnboundidLdapAuthorizationService
      .create(
        Name("LDAP1"),
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
          ignoreLdapConnectivityProblems = false
        ),
        UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com"), "uid"),
        UserGroupsSearchFilterConfig(
          DefaultGroupSearch(
            Dn("ou=Groups,dc=example,dc=com"),
            GroupSearchFilter("(cn=*)"),
            GroupIdAttribute("cn"),
            UniqueMemberAttribute("uniqueMember"),
            groupAttributeIsDN = true,
          ),
          nestedGroupsConfig = None
        )
      )
      .runSyncUnsafe()
      .getOrElse(throw new IllegalStateException("LDAP connection problem"))
  }

  private def usersAndRolesLdapAuthorizationService = {
    UnboundidLdapAuthorizationService
      .create(
        Name("LDAP1"),
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
          ignoreLdapConnectivityProblems = false
        ),
        UserSearchFilterConfig(Dn("ou=Users,dc=example,dc=com"), "uid"),
        UserGroupsSearchFilterConfig(
          DefaultGroupSearch(
            Dn("ou=Roles,dc=example,dc=com"),
            GroupSearchFilter("(cn=*)"),
            GroupIdAttribute("cn"),
            UniqueMemberAttribute("uniqueMember"),
            groupAttributeIsDN = true,
          ),
          Some(NestedGroupsConfig(
            nestedLevels = 1,
            Dn("ou=Roles,dc=example,dc=com"),
            GroupSearchFilter("(cn=*)"),
            UniqueMemberAttribute("uniqueMember"),
            GroupIdAttribute("cn"),
          ))
        )
      )
      .runSyncUnsafe()
      .getOrElse(throw new IllegalStateException("LDAP connection problem"))
  }
}
