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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import eu.timepit.refined.auto._
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyHashingRule.HashedCredentials.{HashedOnlyPassword, HashedUserAndPassword}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeySha256Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, User}
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class AuthKeySha256RuleTests extends BasicAuthenticationTestTemplate(supportingImpersonation = false) {

  override protected def ruleName: String = classOf[AuthKeySha256Rule].getSimpleName

  override protected def ruleCreator: Impersonation => BasicAuthenticationRule[_] = impersonation =>
    new AuthKeySha256Rule(
      BasicAuthenticationRule.Settings(
        HashedUserAndPassword("280ac6f756a64a80143447c980289e7e4c6918b92588c8095c7c3f049a13fbf9")
      ),
      CaseSensitivity.Enabled,
      impersonation
    )
}

class AuthKeySha256RuleAltSyntaxTests extends BasicAuthenticationTestTemplate(supportingImpersonation = true) {

  override protected def ruleName: String = classOf[AuthKeySha256Rule].getSimpleName

  override protected def ruleCreator: Impersonation => BasicAuthenticationRule[_] = impersonation =>
    new AuthKeySha256Rule(
      BasicAuthenticationRule.Settings(
        HashedOnlyPassword(User.Id("logstash"), "76cd2c0d589e224531fc6af2c5850e3c9b2aca6902d813ce598833c7c1b28bee")
      ),
      CaseSensitivity.Enabled,
      impersonation
    )
}
