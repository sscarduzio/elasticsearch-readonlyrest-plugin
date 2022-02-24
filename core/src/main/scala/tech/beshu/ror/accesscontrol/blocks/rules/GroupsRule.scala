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

import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final class GroupsRule(override val settings: BaseGroupsRule.Settings, implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseGroupsRule(settings, caseMappingEquality) {

  override val name: Rule.Name = GroupsRule.Name.name

  override def availableGroupsFrom(userGroups: Set[Group], ruleGroups: Set[Group]): Option[UniqueNonEmptyList[Group]] = {
    UniqueNonEmptyList.fromSet(userGroups intersect ruleGroups)
  }
}

object GroupsRule {
  implicit case object Name extends RuleName[GroupsRule] {
    override val name: Name = Rule.Name("groups")
  }
}
