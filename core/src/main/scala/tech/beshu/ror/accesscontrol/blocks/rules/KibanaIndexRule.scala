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
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaIndexRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{UserMetadataRelatedRule, MatchingAlwaysRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.domain.IndexName

class KibanaIndexRule(val settings: Settings)
  extends UserMetadataRelatedRule with MatchingAlwaysRule {

  override val name: Rule.Name = KibanaIndexRule.name

  override def process(requestContext: RequestContext,
                       blockContext: BlockContext): Task[BlockContext] = Task {
    settings
      .kibanaIndex
      .resolve(requestContext, blockContext)
      .map(blockContext.withKibanaIndex)
      .getOrElse(blockContext)
  }
}

object KibanaIndexRule {
  val name = Rule.Name("kibana_index")

  final case class Settings(kibanaIndex: RuntimeSingleResolvableVariable[IndexName])
}
