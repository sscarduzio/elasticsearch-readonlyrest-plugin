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
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaIndexRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{MatchingAlwaysRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName

class KibanaIndexRule(val settings: Settings)
  extends MatchingAlwaysRule {

  override val name: Rule.Name = KibanaIndexRule.Name.name

  override def process[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[B] = Task {
    settings
      .kibanaIndex
      .resolve(blockContext)
      .map(index => blockContext.withUserMetadata(_.withKibanaIndex(index)))
      .getOrElse(blockContext)
  }
}

object KibanaIndexRule {

  implicit case object Name extends RuleName[KibanaIndexRule] {
    override val name = Rule.Name("kibana_index")
  }

  final case class Settings(kibanaIndex: RuntimeSingleResolvableVariable[ClusterIndexName])
}
