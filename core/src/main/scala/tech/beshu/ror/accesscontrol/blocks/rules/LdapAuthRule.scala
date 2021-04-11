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
package tech.beshu.ror.accesscontrol.blocks.rules

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, NoImpersonationSupport, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality

final class LdapAuthRule(val authentication: LdapAuthenticationRule,
                         val authorization: LdapAuthorizationRule,
                        implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthRule
    with NoImpersonationSupport {

  override val name: Rule.Name = LdapAuthRule.name

  override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    authentication
      .check(blockContext)
      .flatMap {
        case fulfilled: Fulfilled[B] =>
          authorization.check(fulfilled.blockContext)
        case Rejected(_) =>
          Task.now(Rejected())
      }
  }
}

object LdapAuthRule {
  val name = Rule.Name("ldap_auth")
}