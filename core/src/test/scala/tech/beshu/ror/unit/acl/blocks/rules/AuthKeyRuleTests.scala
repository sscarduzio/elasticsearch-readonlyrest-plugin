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
package tech.beshu.ror.unit.acl.blocks.rules

import eu.timepit.refined.auto._
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.ImpersonationSettings
import tech.beshu.ror.accesscontrol.domain.{Credentials, PlainTextSecret, User}
import tech.beshu.ror.utils.UserIdEq

class AuthKeyRuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeyRule].getSimpleName

  override protected val rule = new AuthKeyRule(
    BasicAuthenticationRule.Settings(Credentials(User.Id("logstash"), PlainTextSecret("logstash"))),
    ImpersonationSettings.notConfigured,
    UserIdEq.caseSensitive
  )
}
