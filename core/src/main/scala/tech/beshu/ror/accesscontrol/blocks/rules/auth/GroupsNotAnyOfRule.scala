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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{Name, RuleName}
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity

final class GroupsNotAnyOfRule(override val settings: BaseGroupsRule.Settings,
                               override val userIdCaseSensitivity: CaseSensitivity)
  extends BaseGroupsRule(settings) {

  override val name: Rule.Name = GroupsAndRule.Name.name

}

object GroupsNotAnyOfRule {
  implicit case object Name extends RuleName[GroupsNotAnyOfRule] {
    override val name: Name = Rule.Name("groups_not_any_of")
  }
}
