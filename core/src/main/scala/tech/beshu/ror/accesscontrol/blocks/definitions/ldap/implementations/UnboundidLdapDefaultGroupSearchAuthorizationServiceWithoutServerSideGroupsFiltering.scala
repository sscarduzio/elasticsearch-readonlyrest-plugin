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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{DefaultGroupSearch, NestedGroupsConfig}
import tech.beshu.ror.accesscontrol.domain.{Group, RequestId, User}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.time.Clock

class UnboundidLdapDefaultGroupSearchAuthorizationServiceWithoutServerSideGroupsFiltering private(val underlying: LdapAuthorizationService.WithGroupsFiltering)
  extends LdapAuthorizationService.WithoutGroupsFiltering {
  override def groupsOf(id: User.Id)(implicit requestId: RequestId): Task[UniqueList[Group]] =
    underlying.groupsOf(id, Set.empty)

  override def ldapUsersService: LdapUsersService = underlying.ldapUsersService

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout

  override def id: LdapService.Name = ldapUsersService.id

}

object UnboundidLdapDefaultGroupSearchAuthorizationServiceWithoutServerSideGroupsFiltering {

  def create(id: LdapService#Id,
             ldapUsersService: LdapUsersService,
             poolProvider: UnboundidLdapConnectionPoolProvider,
             connectionConfig: LdapConnectionConfig,
             groupsSearchFilter: DefaultGroupSearch,
             nestedGroupsConfig: Option[NestedGroupsConfig])
            (implicit clock: Clock): Task[Either[ConnectionError, LdapAuthorizationService.WithoutGroupsFiltering]] = {
    UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFiltering
      .create(id, ldapUsersService, poolProvider, connectionConfig, groupsSearchFilter, nestedGroupsConfig)
      .map(_.map(svc => new UnboundidLdapDefaultGroupSearchAuthorizationServiceWithoutServerSideGroupsFiltering(svc)))
  }
}