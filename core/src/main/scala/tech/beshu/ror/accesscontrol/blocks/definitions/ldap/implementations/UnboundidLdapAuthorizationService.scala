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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthorizationService, LdapService, LdapUsersService}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode._

import java.time.Clock

object UnboundidLdapAuthorizationService {

  def create(id: LdapService#Id,
             ldapUsersService: LdapUsersService,
             poolProvider: UnboundidLdapConnectionPoolProvider,
             connectionConfig: LdapConnectionConfig,
             groupsSearchFilter: UserGroupsSearchFilterConfig)
            (implicit clock: Clock): Task[Either[ConnectionError, LdapAuthorizationService]] = {
    groupsSearchFilter.mode match {
      case groupSearch: DefaultGroupSearch if groupSearch.serverSideGroupsFiltering =>
        UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFiltering.create(
          id, ldapUsersService, poolProvider, connectionConfig, groupSearch, groupsSearchFilter.nestedGroupsConfig
        )
      case groupSearch: DefaultGroupSearch  =>
        UnboundidLdapDefaultGroupSearchAuthorizationServiceWithoutServerSideGroupsFiltering.create(
          id, ldapUsersService, poolProvider, connectionConfig, groupSearch, groupsSearchFilter.nestedGroupsConfig
        )
      case groupSearch: GroupsFromUserEntry =>
        UnboundidLdapGroupsFromUserEntryAuthorizationService.create(
            id, ldapUsersService, poolProvider, connectionConfig, groupSearch, groupsSearchFilter.nestedGroupsConfig
          )
    }
  }
}
