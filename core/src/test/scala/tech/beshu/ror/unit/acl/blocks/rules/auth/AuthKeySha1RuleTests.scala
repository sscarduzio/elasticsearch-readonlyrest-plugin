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
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeySha1Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.utils.UserIdEq

class AuthKeySha1RuleTests extends BasicAuthenticationTestTemplate(supportingImpersonation = false) {

  override protected def ruleName: String = classOf[AuthKeySha1Rule].getSimpleName

  override protected def ruleCreator: Impersonation => BasicAuthenticationRule[_] = impersonation =>
    new AuthKeySha1Rule(
      BasicAuthenticationRule.Settings(HashedUserAndPassword("4338fa3ea95532196849ae27615e14dda95c77b1")),
      impersonation,
      UserIdEq.caseSensitive
    )
}

class AuthKeySha1RuleAltSyntaxTests extends BasicAuthenticationTestTemplate(supportingImpersonation = true) {

  override protected def ruleName: String = classOf[AuthKeySha1Rule].getSimpleName

  override protected def ruleCreator: Impersonation => BasicAuthenticationRule[_] = impersonation =>
    new AuthKeySha1Rule(
      BasicAuthenticationRule.Settings(
        HashedOnlyPassword(User.Id("logstash"), "9208e8476a2e8adc584bf2f613842177a39645b4")
      ),
      impersonation,
      UserIdEq.caseSensitive
    )
}