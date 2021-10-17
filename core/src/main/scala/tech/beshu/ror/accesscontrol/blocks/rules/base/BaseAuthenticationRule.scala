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

import cats.Eq
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.ImpersonationSettingsBasedSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.request.RequestContextOps._

trait BaseAuthenticationRule extends AuthenticationRule with ImpersonationSettingsBasedSupport {

  protected def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]]

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
    authenticate(blockContext)
  }

  override protected[base] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
    implicit val eqUserId: Eq[User.Id] = caseMappingEquality.toOrder
    val requestContext = blockContext.requestContext
    requestContext.impersonateAs match {
      case Some(theImpersonatedUserId) => impersonate(as = theImpersonatedUserId, blockContext)
      case None => tryToAuthenticate(blockContext)
    }
  }

}
