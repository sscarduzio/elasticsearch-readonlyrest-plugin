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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessPermissions.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

abstract class BaseKibanaRule2(val settings: BaseKibanaRule.Settings)
  extends RegularRule with KibanaRelatedRule with RequestIdAwareLogging {

  def checkAccess(bc: BlockContext, kibanaIndex: KibanaIndexName): MatchVerdict = {
    given BlockContext = bc

    if (settings.access === Unrestricted) {
      return MatchVerdict.Allowed("Unrestricted access configured")
    }

    val resource = RequestClassifier.classifyResource(bc, kibanaIndex, settings.rorIndex)
    val action = RequestClassifier.classifyAction(bc)
    logger.debug(s"Resource: $resource, Action: $action, Access: ${settings.access}")

    val permission = PermissionTable.lookup(settings.access, resource, bc)
    logger.debug(s"Permission from table: ${permission.label}")

    val nonStrictEligible = resource == ResourceCategory.KibanaIndex &&
      RequestClassifier.isNonStrictEligible(bc, kibanaIndex)

    val verdict = PermissionEvaluator.evaluate(permission, action, settings.access, resource, nonStrictEligible)
    logger.debug(s"Verdict: $verdict")

    verdict
  }

  protected def shouldMatch(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    checkAccess(bc, kibanaIndex).isAllowed
  }
}
