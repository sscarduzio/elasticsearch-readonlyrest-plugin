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

import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}

private[mocks] final case class SimpleMocksProvider(mocks: AuthServicesMocks)
  extends MocksProvider {

  override def ldapServiceWith(id: LdapService.Name)
                              (implicit context: RequestId): Option[LdapServiceMock] =
    mocks.ldapMocks.get(id)

  override def externalAuthenticationServiceWith(id: ExternalAuthenticationService.Name)
                                                (implicit context: RequestId): Option[ExternalAuthenticationServiceMock] =
    mocks.externalAuthenticationServiceMocks.get(id)

  override def externalAuthorizationServiceWith(id: ExternalAuthorizationService.Name)
                                               (implicit context: RequestId): Option[ExternalAuthorizationServiceMock] =
    mocks.externalAuthorizationServiceMocks.get(id)
}