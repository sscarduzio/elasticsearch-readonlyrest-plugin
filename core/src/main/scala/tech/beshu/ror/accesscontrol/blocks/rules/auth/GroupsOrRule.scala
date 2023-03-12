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
import tech.beshu.ror.accesscontrol.domain.{GroupsLogic, PermittedGroups}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final class GroupsOrRule(override val settings: BaseGroupsRule.Settings,
                         implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseGroupsRule(settings, caseMappingEquality) {

  override val name: Rule.Name = GroupsOrRule.Name.name

  override protected def calculateAllowedGroupsForUser(userGroups: UniqueNonEmptyList[GroupName],
                                                       permittedGroups: PermittedGroups): Option[UniqueNonEmptyList[GroupName]] = {
    GroupsLogic.Or(permittedGroups).availableGroupsFrom(userGroups)
  }
}

object GroupsOrRule {
  implicit case object Name extends RuleName[GroupsOrRule] {
    override val name: Name = Rule.Name("groups_or")
  }

  case object DeprecatedName extends RuleName[GroupsOrRule] {
    override val name = Rule.Name("groups")
  }

}
