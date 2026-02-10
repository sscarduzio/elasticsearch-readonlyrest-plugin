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
package tech.beshu.ror.accesscontrol.blocks.mocks

import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalGroupsProviderService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalGroupsProviderServiceMock.ExternalGroupsProviderServiceUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock.LdapUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalGroupsProviderServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.domain.{Group, RequestId, User}
import tech.beshu.ror.syntax.*

trait MocksProvider {

  def ldapServiceWith(id: LdapService#Id)
                     (implicit context: RequestId): Option[LdapServiceMock]

  def externalAuthenticationServiceWith(id: ExternalAuthenticationService#Id)
                                       (implicit context: RequestId): Option[ExternalAuthenticationServiceMock]

  def externalGroupsProviderServiceWith(id: ExternalGroupsProviderService#Id)
                                       (implicit context: RequestId): Option[ExternalGroupsProviderServiceMock]
}
object MocksProvider {

  final case class LdapServiceMock(users: Set[LdapUserMock])
  object LdapServiceMock {
    final case class LdapUserMock(id: User.Id, groups: Set[Group])
  }

  final case class ExternalAuthenticationServiceMock(users: Set[ExternalAuthenticationUserMock])
  object ExternalAuthenticationServiceMock {
    final case class ExternalAuthenticationUserMock(id: User.Id)
  }

  final case class ExternalGroupsProviderServiceMock(users: Set[ExternalGroupsProviderServiceUserMock])
  object ExternalGroupsProviderServiceMock {
    final case class ExternalGroupsProviderServiceUserMock(id: User.Id, groups: Set[Group])
  }
}

object NoOpMocksProvider extends MocksProvider {
  override def ldapServiceWith(id: LdapService.Name)
                              (implicit context: RequestId): Option[LdapServiceMock] = None

  override def externalAuthenticationServiceWith(id: ExternalAuthenticationService.Name)
                                                (implicit context: RequestId): Option[ExternalAuthenticationServiceMock] = None

  override def externalGroupsProviderServiceWith(id: ExternalGroupsProviderService.Name)
                                                (implicit context: RequestId): Option[ExternalGroupsProviderServiceMock] = None
}
