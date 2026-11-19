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

import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.BaseKibanaRule.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

trait KibanaRelatedRule {
  this: Rule =>
}

abstract class BaseKibanaRule(val settings: Settings)
    extends RegularRule
    with KibanaRelatedRule
    with RequestIdAwareLogging {

  protected def isAllowed(blockContext: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    val request = new KibanaRequest(blockContext, kibanaIndex)
    val allowed = KibanaAccessTable.isAllowed(settings.access, request)
    given BlockContext = blockContext
    logger.debug(s"Kibana access table, target: ${request.target.name}, allowed: ${allowed.show}")
    allowed
  }

}

object BaseKibanaRule {

  abstract class Settings(val access: KibanaAccess, val rorIndex: RorSettingsIndex)

}
