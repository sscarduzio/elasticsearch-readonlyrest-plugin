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
package tech.beshu.ror.integration.suites

import tech.beshu.ror.integration.suites.base.PkiAuthSuite
import tech.beshu.ror.utils.containers.SecurityType
import tech.beshu.ror.utils.containers.SecurityType.RorSecurity
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.{Attributes, ClientAuthentication, RestSsl}
import tech.beshu.ror.utils.containers.images.domain.Enabled

/** PKI over TLS terminated by ReadonlyREST's own HTTP transport. */
class PkiAuthWithRorSslSuite extends PkiAuthSuite {

  override protected def pkiSecurityType: SecurityType = RorSecurity(
    Attributes.default.copy(
      rorSettingsFileName = rorSettingsFileName,
      // optional, not required: a caller without a certificate has to reach the ACL and fall through
      // to the password block, which is the behaviour the suite asserts
      restSsl = Enabled.Yes(RestSsl.RorPki(ClientAuthentication.Optional))
    )
  )

}
