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
import tech.beshu.ror.acl.domain.Address
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.values.Variable
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.request.RequestContext

class LocalHostsRule(val settings: Settings)
  extends BaseHostsRule {

  override val name: Rule.Name = LocalHostsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = {
    checkAllowedAddresses(requestContext, blockContext)(
      allowedAddresses = settings.allowedAddresses,
      addressToCheck = requestContext.localAddress
    ).map(condition => RuleResult.fromCondition(blockContext)(condition))
  }

}

object LocalHostsRule {
  val name = Rule.Name("hosts_local")

  final case class Settings(allowedAddresses: NonEmptySet[Variable[Address]])

}