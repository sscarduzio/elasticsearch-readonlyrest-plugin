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

import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessRule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessRule._
import tech.beshu.ror.accesscontrol.domain._

class KibanaAccessRuleTests
  extends BaseKibanaAccessBasedTests[KibanaAccessRule, KibanaAccessRule.Settings] {

  override protected def createRuleFrom(settings: Settings): KibanaAccessRule = new KibanaAccessRule(settings)

  override protected def settingsOf(access: KibanaAccess,
                                    customKibanaIndex: Option[KibanaIndexName] = None): Settings =
    Settings(access, RorConfigurationIndex(rorIndex))

  override protected def defaultOutputBlockContextAssertion(settings: Settings,
                                                            indices: Set[ClusterIndexName],
                                                            customKibanaIndex: Option[KibanaIndexName]): BlockContext => Unit =
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
