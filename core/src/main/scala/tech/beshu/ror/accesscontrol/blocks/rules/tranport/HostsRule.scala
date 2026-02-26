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
package tech.beshu.ror.accesscontrol.blocks.rules.tranport

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.HostsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.Address

class HostsRule(val settings: Settings,
                resolver: HostnameResolver)
  extends BaseHostsRule(resolver) {

  override val name: Rule.Name = HostsRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    val requestContext = blockContext.requestContext
    requestContext.xForwardedForHeaderValue match {
      case Some(xForwardedHeaderValue) if settings.acceptXForwardedForHeader =>
        checkAllowedAddresses(blockContext)(
          allowedAddresses = settings.allowedHosts,
          addressToCheck = xForwardedHeaderValue
        ).flatMap {
          case true =>
            Task.now(Decision.Permitted(blockContext))
          case false =>
            checkRemoteAddress(blockContext)
        }
      case _ =>
        checkRemoteAddress(blockContext)
    }
  }

  private def checkRemoteAddress[B <: BlockContext](blockContext: B): Task[Decision[B]] = {
    implicit val blockContextImpl: B = blockContext
    blockContext.requestContext.restRequest.remoteAddress match {
      case Some(remoteAddress) =>
        checkAllowedAddresses(blockContext)(
          allowedAddresses = settings.allowedHosts,
          addressToCheck = remoteAddress
        ).map(condition => Decision.permit(`with` = blockContext)(when = condition))
      case None =>
        logger.warn(s"Remote address is unavailable!")
        Task.now(Denied(Cause.NotAuthorized))
    }
  }
}

object HostsRule {

  implicit case object Name extends RuleName[HostsRule] {
    override val name: Rule.Name = Rule.Name("hosts")
  }

  final case class Settings(allowedHosts: NonEmptySet[RuntimeMultiResolvableVariable[Address]],
                            acceptXForwardedForHeader: Boolean)

}
