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
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyPBKDF2WithHmacSHA512Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, User}
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class AuthKeyPBKDF2WithHmacSHA512RuleTests
  extends BasicAuthenticationTestTemplate(supportingImpersonation = false) {

  override protected def ruleName: String = classOf[AuthKeyPBKDF2WithHmacSHA512Rule].getSimpleName

  override protected def ruleCreator: Impersonation => BasicAuthenticationRule[_] = impersonation =>
    new AuthKeyPBKDF2WithHmacSHA512Rule(
      BasicAuthenticationRule.Settings(
        HashedUserAndPassword("KhIxF5EEYkH5GPX51zTRIR4cHqhpRVALSmTaWE18mZEL2KqCkRMeMU4GR848mGq4SDtNvsybtJ/sZBuX6oFaSg==")
      ),
      CaseSensitivity.Enabled,
      impersonation,
    )
}

class AuthKeyPBKDF2WithHmacSHA512RuleAltSyntaxTests
  extends BasicAuthenticationTestTemplate(supportingImpersonation = true) {

  override protected def ruleName: String = classOf[AuthKeyPBKDF2WithHmacSHA512Rule].getSimpleName

  override protected def ruleCreator: Impersonation => BasicAuthenticationRule[_] = impersonation =>
    new AuthKeyPBKDF2WithHmacSHA512Rule(
      BasicAuthenticationRule.Settings(
        HashedOnlyPassword(
          User.Id("logstash"),
          "JltDNAoXNtc7MIBs2FYlW0o1f815ucj+bel3drdAk2yOufg2PNfQ51qr0EQ6RSkojw/DzrDLFDeXONumzwKjOA=="
        )
      ),
      CaseSensitivity.Enabled,
      impersonation,
    )
}
