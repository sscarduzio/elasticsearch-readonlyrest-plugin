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

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.accesscontrol.blocks.rules.XForwardedForRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.request.RequestContextOps._

class XForwardedForRule(val settings: Settings,
                        resolver: HostnameResolver)
  extends BaseHostsRule(resolver) {

  override val name: Rule.Name = XForwardedForRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    blockContext.requestContext.xForwardedForHeaderValue match {
      case Some(xForwardedForAddress) =>
        checkAllowedAddresses(blockContext)(
          allowedAddresses = settings.allowedAddresses,
          addressToCheck = xForwardedForAddress
        ).map(condition => RuleResult.fromCondition(blockContext)(condition))
      case None =>
        Task.now(Rejected())
    }
  }

}

object XForwardedForRule {
  val name = Rule.Name("x_forwarded_for")

  final case class Settings(allowedAddresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]])

}
