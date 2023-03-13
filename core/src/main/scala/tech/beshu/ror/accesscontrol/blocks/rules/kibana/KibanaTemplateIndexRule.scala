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
package tech.beshu.ror.accesscontrol.blocks.rules.kibana

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{MatchingAlwaysRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaTemplateIndexRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.IndexName

class KibanaTemplateIndexRule(val settings: Settings)
  extends MatchingAlwaysRule {

  override val name: Rule.Name = KibanaTemplateIndexRule.Name.name

  override def process[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[B] = Task {
    settings
      .kibanaTemplateIndex
      .resolve(blockContext)
      .map(index => blockContext.withUserMetadata(_.withKibanaTemplateIndex(index)))
      .getOrElse(blockContext)
  }
}

object KibanaTemplateIndexRule {

  implicit case object Name extends RuleName[KibanaTemplateIndexRule] {
    override val name = Rule.Name("kibana_template_index")
  }

  final case class Settings(kibanaTemplateIndex: RuntimeSingleResolvableVariable[IndexName.Kibana])
}
