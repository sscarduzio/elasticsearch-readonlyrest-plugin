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

import tech.beshu.ror.accesscontrol.blocks.rules.GroupsRule.orderingByStringValue
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality

import scala.collection.immutable.SortedSet

final class GroupsAndRule(override val settings: GroupsRule.Settings,
                          implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends GroupsRule(settings, caseMappingEquality) {

  override def intersect(set1: Set[Group], set2: Set[Group]): SortedSet[Group] = {
    if (set1.equals(set2)) {
      return SortedSet.empty[Group] ++ set2
    }
    SortedSet.empty
  }
}

object GroupsAndRule {
  implicit case object Name extends RuleName[GroupsRule] {
    override val name: Name = Rule.Name("groups_all")
  }

}
