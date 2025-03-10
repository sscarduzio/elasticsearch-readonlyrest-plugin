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
package tech.beshu.ror.accesscontrol.blocks.rules.auth

import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule.{Creator, Settings}
import tech.beshu.ror.accesscontrol.domain.*

class CombinedLogicGroupsRule(override val settings: Settings[GroupsLogic.Combined])
                             (override implicit val userIdCaseSensitivity: CaseSensitivity)
  extends BaseGroupsRule[GroupsLogic.Combined](CombinedLogicGroupsRule.Name.name, settings)

object CombinedLogicGroupsRule {

  case object Name extends RuleName[AllOfGroupsRule] {
    override val name: Rule.Name = Rule.Name("groups_combined")
  }

  implicit val combinedLogicCreator: Creator[GroupsLogic.Combined] = new CombinedLogicGroupsRule(_)(_)

}
