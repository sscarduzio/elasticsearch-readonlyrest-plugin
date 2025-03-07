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

class AllOfGroupsRule(override val name: Rule.Name, override val settings: Settings[GroupsLogic.AllOf])
                     (override implicit val userIdCaseSensitivity: CaseSensitivity)
  extends BaseGroupsRule[GroupsLogic.AllOf](name, settings)

object AllOfGroupsRule {
  implicit val allOfTypeInfo: Creator[GroupsLogic.AllOf] = new AllOfGroupsRule(_, _)(_)

  case object ExtendedSyntaxName extends RuleName[AllOfGroupsRule] {
    override val name: Name = Rule.Name("all_of")
  }

  case object SimpleSyntaxName extends RuleName[AllOfGroupsRule] {
    override val name: Name = Rule.Name("groups_all_of")
  }

  case object DeprecatedSimpleSyntaxNameV2 extends RuleName[AllOfGroupsRule] {
    override val name: Name = Rule.Name("groups_and")
  }

  case object DeprecatedSimpleSyntaxNameV1 extends RuleName[AllOfGroupsRule] {
    override val name: Name = Rule.Name("roles_and")
  }
}