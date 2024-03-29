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
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaHideAppsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.KibanaApp
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class KibanaHideAppsRule(val settings: Settings)
  extends MatchingAlwaysRule with KibanaRelatedRule {

  override val name: Rule.Name = KibanaHideAppsRule.Name.name

  override def process[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[B] = Task {
    blockContext.withUserMetadata(_.withHiddenKibanaApps(settings.kibanaAppsToHide))
  }
}

object KibanaHideAppsRule {

  implicit case object Name extends RuleName[KibanaHideAppsRule] {
    override val name = Rule.Name("kibana_hide_apps")
  }

  final case class Settings(kibanaAppsToHide: UniqueNonEmptyList[KibanaApp])

}
