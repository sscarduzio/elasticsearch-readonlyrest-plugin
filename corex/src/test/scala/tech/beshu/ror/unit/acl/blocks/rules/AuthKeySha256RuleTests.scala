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

import tech.beshu.ror.acl.blocks.rules.{AuthKeySha256Rule, BasicAuthenticationRule}
import tech.beshu.ror.acl.aDomain.Secret

class AuthKeySha256RuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha256Rule].getSimpleName
  override protected val rule = new AuthKeySha256Rule(BasicAuthenticationRule.Settings(Secret("280ac6f756a64a80143447c980289e7e4c6918b92588c8095c7c3f049a13fbf9")))
}
