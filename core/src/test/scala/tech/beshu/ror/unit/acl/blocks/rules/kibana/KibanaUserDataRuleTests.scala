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
package tech.beshu.ror.unit.acl.blocks.rules.kibana

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaUserDataRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.IndexName.Kibana
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, RorConfigurationIndex}

class KibanaUserDataRuleTests
  extends AnyWordSpec
    with BaseKibanaAccessBasedTests[KibanaUserDataRule, KibanaUserDataRule.Settings] {

  override protected def createRuleFrom(settings: KibanaUserDataRule.Settings): KibanaUserDataRule =
    new KibanaUserDataRule(settings)

  override protected def settingsOf(access: domain.KibanaAccess,
                                    customKibanaIndex: Option[IndexName.Kibana] = None): KibanaUserDataRule.Settings =
    KibanaUserDataRule.Settings(
      access = access,
      kibanaIndex = AlreadyResolved(customKibanaIndex.getOrElse(ClusterIndexName.Local.kibanaDefault)),
      kibanaTemplateIndex = None,
      appsToHide = Set.empty,
      rorIndex = RorConfigurationIndex(rorIndex)
    )

  override protected def defaultOutputBlockContextAssertion(settings: KibanaUserDataRule.Settings,
                                                            indices: Set[domain.ClusterIndexName],
                                                            customKibanaIndex: Option[Kibana]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        kibanaAccess = Some(settings.access),
        kibanaIndex = Some(kibanaIndexFrom(customKibanaIndex)),
        indices = indices
      )(
        blockContext
      )
    }
}
