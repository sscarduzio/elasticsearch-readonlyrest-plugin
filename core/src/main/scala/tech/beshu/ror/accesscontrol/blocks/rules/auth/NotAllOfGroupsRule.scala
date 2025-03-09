
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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule.{Creator, Settings}
import tech.beshu.ror.accesscontrol.domain.*

class NotAllOfGroupsRule(override val settings: Settings[GroupsLogic.NotAllOf])
                        (override implicit val userIdCaseSensitivity: CaseSensitivity)
  extends BaseGroupsRule[GroupsLogic.NotAllOf](NotAllOfGroupsRule.SimpleSyntaxName.name, settings)

object NotAllOfGroupsRule {
  implicit val notAllOfCreator: Creator[GroupsLogic.NotAllOf] = new NotAllOfGroupsRule(_)(_)

  case object ExtendedSyntaxName extends RuleName[NotAllOfGroupsRule] {
    override val name: Name = Rule.Name("not_all_of")
  }

  case object SimpleSyntaxName extends RuleName[NotAllOfGroupsRule] {
    override val name: Name = Rule.Name("groups_not_all_of")
  }
}