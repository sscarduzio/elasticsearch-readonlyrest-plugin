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

class AnyOfGroupsRule(override val settings: Settings[GroupsLogic.AnyOf])
                     (override implicit val userIdCaseSensitivity: CaseSensitivity)
  extends BaseGroupsRule[GroupsLogic.AnyOf](AnyOfGroupsRule.SimpleSyntaxName.name, settings)


object AnyOfGroupsRule {
  implicit val anyOfCreator: Creator[GroupsLogic.AnyOf] = new AnyOfGroupsRule(_)(_)

  case object ExtendedSyntaxName extends RuleName[AnyOfGroupsRule] {
    override val name: Name = Rule.Name("any_of")
  }

  case object SimpleSyntaxName extends RuleName[AnyOfGroupsRule] {
    override val name: Name = Rule.Name("groups_any_of")
  }

  case object DeprecatedSimpleSyntaxNameV3 extends RuleName[AnyOfGroupsRule] {
    override val name: Name = Rule.Name("groups_or")
  }

  case object DeprecatedSimpleSyntaxNameV2 extends RuleName[AnyOfGroupsRule] {
    override val name: Name = Rule.Name("groups")
  }

  case object DeprecatedSimpleSyntaxNameV1 extends RuleName[AnyOfGroupsRule] {
    override val name: Name = Rule.Name("roles")
  }
}