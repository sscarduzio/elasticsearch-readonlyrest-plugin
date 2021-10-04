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
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials.{HashedOnlyPassword, HashedUserAndPassword}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.ImpersonationSettings
import tech.beshu.ror.accesscontrol.blocks.rules.{AuthKeySha512Rule, BasicAuthenticationRule}
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.utils.UserIdEq

class AuthKeySha512RuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha512Rule].getSimpleName

  override protected val rule = new AuthKeySha512Rule(
    BasicAuthenticationRule.Settings(
      HashedUserAndPassword("3586d5752240fd09e967383d3f1bad025bbc6953ba7c6d2135670631b4e326fee0cc8bd81addb9f6de111b9c380505b5ea0531598c21b0906d8e726f24e0dbe2")
    ),
    ImpersonationSettings(List.empty, NoOpMocksProvider),
    UserIdEq.caseSensitive
  )
}

class AuthKeySha512RuleAltSyntaxTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha512Rule].getSimpleName

  override protected val rule = new AuthKeySha512Rule(
    BasicAuthenticationRule.Settings(
      HashedOnlyPassword(User.Id("logstash"), "2963e577145fb7f675c6726800691b3432020f8373cc5a3e8b30ca0856047846d10c96b7cfe64ed750637e09d7266e6eb464628995eed5368ef4780868f230ea")
    ),
    ImpersonationSettings(List.empty, NoOpMocksProvider),
    UserIdEq.caseSensitive
  )
}