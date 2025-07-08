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
package tech.beshu.ror.accesscontrol.factory

import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.domain.{LocalUsers, RequestId}
import tech.beshu.ror.accesscontrol.factory.RorDependencies.ImpersonationWarningsReader

import scala.annotation.unused

final case class RorDependencies(services: RorDependencies.Services,
                                 localUsers: LocalUsers,
                                 impersonationWarningsReader: ImpersonationWarningsReader)

object RorDependencies {
  def noOp: RorDependencies = RorDependencies(RorDependencies.Services.empty, LocalUsers.empty, NoOpImpersonationWarningsReader)

  final case class Services(authenticationServices: Seq[ExternalAuthenticationService#Id],
                            authorizationServices: Seq[ExternalAuthorizationService#Id],
                            ldaps: Seq[LdapService#Id])
  object Services {
    def empty: Services = Services(Seq.empty, Seq.empty, Seq.empty)
  }

  trait ImpersonationWarningsReader {
    def read()
            (implicit requestId: RequestId): List[ImpersonationWarning]
  }

  object NoOpImpersonationWarningsReader extends ImpersonationWarningsReader {
    override def read()
                     (implicit @unused requestId: RequestId): List[ImpersonationWarning] = List.empty
  }

}
