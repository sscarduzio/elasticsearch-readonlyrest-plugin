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
package tech.beshu.ror.accesscontrol.blocks.rules.base

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{AuthRule, AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}

abstract class BaseComposedAuthenticationAndAuthorizationRule(authenticationRule: AuthenticationRule,
                                                              authorizationRule: AuthorizationRule)
  extends AuthRule
    with AuthenticationRule with AuthenticationImpersonationCustomSupport
    with AuthorizationRule with AuthorizationImpersonationCustomSupport {

  override protected[base] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    authenticationRule.authenticate(blockContext)

  override protected[base] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    authorizationRule.authorize(blockContext)
}
