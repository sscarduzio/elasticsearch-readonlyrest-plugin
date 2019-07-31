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

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.HostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.acl.domain.Address
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._

class HostsRule(val settings: Settings)
  extends BaseHostsRule {

  override val name: Rule.Name = HostsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = {
    requestContext.xForwardedForHeaderValue match {
      case Some(xForwardedHeaderValue) if settings.acceptXForwardedForHeader =>
        checkAllowedAddresses(requestContext, blockContext)(
          allowedAddresses = settings.allowedHosts,
          addressToCheck = xForwardedHeaderValue
        ).flatMap {
          case true =>
            Task.now(RuleResult.Fulfilled(blockContext))
          case false =>
            checkRemoteAddress(requestContext, blockContext)
        }
      case _ =>
        checkRemoteAddress(requestContext, blockContext)
    }
  }

  private def checkRemoteAddress(requestContext: RequestContext,
                                 blockContext: BlockContext) = {
    requestContext.remoteAddress match {
      case Some(remoteAddress) =>
        checkAllowedAddresses(requestContext, blockContext)(
          allowedAddresses = settings.allowedHosts,
          addressToCheck = remoteAddress
        ).map(condition => RuleResult.fromCondition(blockContext)(condition))
      case None =>
        logger.warn("Remote address is unavailable!")
        Task.now(Rejected())
    }
  }
}

object HostsRule {

  val name: Rule.Name = Rule.Name("hosts")

  final case class Settings(allowedHosts: NonEmptySet[RuntimeMultiResolvableVariable[Address]],
                            acceptXForwardedForHeader: Boolean)

}
