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
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials.{HashedOnlyPassword, HashedUserAndPassword}
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeySha256Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.utils.UserIdEq

class AuthKeySha256RuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha256Rule].getSimpleName

  override protected val rule = new AuthKeySha256Rule(
    BasicAuthenticationRule.Settings(HashedUserAndPassword("280ac6f756a64a80143447c980289e7e4c6918b92588c8095c7c3f049a13fbf9")),
    Impersonation.Disabled,
    UserIdEq.caseSensitive
  )
}

class AuthKeySha256RuleAltSyntaxTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha256Rule].getSimpleName

  override protected val rule = new AuthKeySha256Rule(
    BasicAuthenticationRule.Settings(
      HashedOnlyPassword(User.Id("logstash"), "76cd2c0d589e224531fc6af2c5850e3c9b2aca6902d813ce598833c7c1b28bee")
    ),
    Impersonation.Disabled,
    UserIdEq.caseSensitive
  )
}
