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
package tech.beshu.ror.configuration

import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.domain.LocalUsers
import tech.beshu.ror.accesscontrol.logging.audit.AuditingTool
import tech.beshu.ror.configuration.RorConfig.ImpersonationWarningsReader

final case class RorConfig(services: RorConfig.Services,
                           localUsers: LocalUsers,
                           impersonationWarningsReader: ImpersonationWarningsReader,
                           auditingSettings: Option[AuditingTool.Settings])

object RorConfig {
  def disabled: RorConfig = RorConfig(RorConfig.Services.empty, LocalUsers.empty, NoOpImpersonationWarningsReader, None)

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
                     (implicit requestId: RequestId): List[ImpersonationWarning] = List.empty
  }

}
