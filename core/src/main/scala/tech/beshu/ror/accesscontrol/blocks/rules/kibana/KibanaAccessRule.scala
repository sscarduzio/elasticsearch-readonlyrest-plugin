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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*

@deprecated("[ROR] This rule is deprecated. Users should use KibanaUserDataRule instead.", "1.48.0")
class KibanaAccessRule(override val settings: Settings)
  extends BaseKibanaRule(settings) {

  override val name: Rule.Name = KibanaAccessRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    val kibanaIndex = kibanaIndexFrom(blockContext)
    if (shouldMatch(blockContext.requestContext, kibanaIndex))
      matched(blockContext, kibanaIndex)
    else
      Rejected[B]()
  }

  private def matched[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                               kibanaIndex: KibanaIndexName): Fulfilled[B] = {
    RuleResult.Fulfilled[B] {
      blockContext.withUserMetadata { metadata =>
        metadata
          .withKibanaAccess(settings.access)
          .withKibanaIndex(kibanaIndex)
      }
    }
  }

  private def kibanaIndexFrom(blockContext: BlockContext): KibanaIndexName = {
    blockContext.userMetadata.kibanaIndex.getOrElse(ClusterIndexName.Local.kibanaDefault)
  }
}

object KibanaAccessRule {

  implicit case object Name extends RuleName[KibanaAccessRule] {
    override val name = Rule.Name("kibana_access")
  }

  final case class Settings(override val access: KibanaAccess,
                            override val rorIndex: RorConfigurationIndex)
    extends BaseKibanaRule.Settings(access, rorIndex)
}
