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
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.SimpleAuthenticationImpersonationSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.SimpleAuthenticationImpersonationSupport.ImpersonationResult
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}

trait BaseAuthenticationRule extends AuthenticationRule with SimpleAuthenticationImpersonationSupport {

  protected def tryToAuthenticateUser[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]]

  override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    tryToImpersonateUser(blockContext)
      .flatMap {
        case ImpersonationResult.WontImpersonate() => tryToAuthenticateUser(blockContext)
        case ImpersonationResult.Handled(result) => Task.now(result)
      }
  }

}
