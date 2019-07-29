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
package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, NoImpersonationSupport, RuleResult}
import tech.beshu.ror.acl.request.RequestContext

class LdapAuthRule(val authentication: LdapAuthenticationRule,
                   val authorization: LdapAuthorizationRule)
  extends AuthenticationRule
    with NoImpersonationSupport
    with AuthorizationRule {

  override val name: Rule.Name = LdapAuthRule.name

  override def tryToAuthenticate(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] = {
    authentication
      .check(requestContext, blockContext)
      .flatMap {
        case Fulfilled(modifiedBlockContext) =>
          authorization.check(requestContext, modifiedBlockContext)
        case Rejected(_) =>
          Task.now(Rejected())
      }
  }
}

object LdapAuthRule {
  val name = Rule.Name("ldap_auth")
}