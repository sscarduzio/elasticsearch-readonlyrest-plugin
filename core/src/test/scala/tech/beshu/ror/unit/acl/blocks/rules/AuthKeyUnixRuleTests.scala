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

import tech.beshu.ror.accesscontrol.blocks.rules.{AuthKeyUnixRule, BasicAuthenticationRule}
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.utils.TestsUtils
import tech.beshu.ror.utils.TestsUtils._

class AuthKeyUnixRuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeyUnixRuleTests].getSimpleName

  override protected val rule = new AuthKeyUnixRule(
    BasicAuthenticationRule.Settings(
      AuthKeyUnixRule.UnixHashedCredentials(
        User.Id("logstash".nonempty),
        "$6$rounds=65535$d07dnv4N$jh8an.nDSXG6PZlfVh5ehigYL8.5gtV.9yoXAOYFHTQvwPWhBdEIOxnS8tpbuIAk86shjJiqxeap5o0A1PoFI/".nonempty
      )),
    Nil,
    TestsUtils.userIdEq
  )
}
