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
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaTemplateIndexRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{MatchingAlwaysRule, RegularRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation}
import tech.beshu.ror.accesscontrol.request.RequestContext

class KibanaTemplateIndexRule(val settings: Settings)
  extends RegularRule with MatchingAlwaysRule {

  override val name: Rule.Name = KibanaTemplateIndexRule.name

  override def process[T <: Operation](requestContext: RequestContext[T],
                                       blockContext: BlockContext[T]): Task[BlockContext[T]] = Task {
    settings
      .kibanaTemplateIndex
      .resolve(requestContext, blockContext)
      .map(blockContext.withKibanaTemplateIndex)
      .getOrElse(blockContext)
  }
}

object KibanaTemplateIndexRule {
  val name = Rule.Name("kibana_template_index")

  final case class Settings(kibanaTemplateIndex: RuntimeSingleResolvableVariable[IndexName])
}
