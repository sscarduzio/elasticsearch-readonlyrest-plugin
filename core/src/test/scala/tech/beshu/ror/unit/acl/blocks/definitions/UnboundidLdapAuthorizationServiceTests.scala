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

import com.dimafeng.testcontainers.ForAllTestContainer
import eu.timepit.refined.api.Refined
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.definitions.ldap.Dn
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, LdapHost}
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.DefaultGroupSearch
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations._
import tech.beshu.ror.acl.domain.{Group, Secret, User}
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

class UnboundidLdapAuthorizationServiceTests extends WordSpec with ForAllTestContainer with Inside {

  override val container: LdapContainer = new LdapContainer("LDAP1", "/test_example.ldif")

  "An LdapAuthorizationService" should {
    "has method to provide user groups" which {
      "returns non empty set of groups" when {
        "user has groups" in {
          authorizationService.groupsOf(User.Id("morgan".nonempty)).runSyncUnsafe() should be {
            Set(Group("group2".nonempty), Group("group3".nonempty), Group("groupAll".nonempty))
          }
        }
      }
      "returns empty set of groups" when {
        "user has no groups" in {
          authorizationService.groupsOf(User.Id("devito".nonempty)).runSyncUnsafe() should be (Set.empty[Group])
        }
        "there is no user with given name" in {
          authorizationService.groupsOf(User.Id("unknown".nonempty)).runSyncUnsafe() should be (Set.empty[Group])
        }
      }
    }
  }

  private def authorizationService = {
    UnboundidLdapAuthorizationService
      .create(
        Name("ldap1".nonempty),
        LdapConnectionConfig(
          ConnectionMethod.SingleServer(LdapHost.from(s"ldap://${container.ldapHost}:${container.ldapPort}").get),
          Refined.unsafeApply(10),
          Refined.unsafeApply(5 seconds),
          Refined.unsafeApply(5 seconds),
          trustAllCerts = false,
          BindRequestUser.CustomUser(
            Dn("cn=admin,dc=example,dc=com".nonempty),
            Secret("password")
          )
        ),
        UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com".nonempty), "uid".nonempty),
        UserGroupsSearchFilterConfig(DefaultGroupSearch(
          Dn("ou=Groups,dc=example,dc=com".nonempty),
          "cn".nonempty,
          "uniqueMember".nonempty,
          "(cn=*)".nonempty
        ))
      )
      .runSyncUnsafe()
      .right.getOrElse(throw new IllegalStateException("LDAP connection problem"))
  }
}
