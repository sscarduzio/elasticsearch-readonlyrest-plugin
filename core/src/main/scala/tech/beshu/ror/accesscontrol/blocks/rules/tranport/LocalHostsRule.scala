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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.LocalHostsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Address

class LocalHostsRule(val settings: Settings,
                     resolver: HostnameResolver)
  extends BaseHostsRule(resolver) {

  override val name: Rule.Name = LocalHostsRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    checkAllowedAddresses(blockContext)(
      allowedAddresses = settings.allowedAddresses,
      addressToCheck = blockContext.requestContext.restRequest.localAddress
    ).map(condition => RuleResult.resultBasedOnCondition(blockContext)(condition))
  }

}

object LocalHostsRule {

  implicit case object Name extends RuleName[LocalHostsRule] {
    override val name = Rule.Name("hosts_local")
  }

  final case class Settings(allowedAddresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]])
}