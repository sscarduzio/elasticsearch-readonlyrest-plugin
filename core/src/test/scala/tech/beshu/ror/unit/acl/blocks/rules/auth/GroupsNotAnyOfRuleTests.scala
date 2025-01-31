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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithoutGroupsMapping
import tech.beshu.ror.accesscontrol.blocks.rules.auth.BaseGroupsRule.Settings as GroupsRulesSettings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{BaseGroupsRule, GroupsNotAnyOfRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.GroupsLogicResolver
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class GroupsNotAnyOfRuleTests extends BaseGroupsNegativeRuleTests {

  override def createRule(settings: GroupsRulesSettings, caseSensitivity: CaseSensitivity): BaseGroupsRule = {
    new GroupsNotAnyOfRule(settings, caseSensitivity)
  }

  override def groupsLogicResolver(groupIds: UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]): GroupsLogicResolver = {
    GroupsLogicResolver(ResolvableGroupIds(groupIds), GroupsLogic.NotAnyOf.apply)
  }

  "A GroupsNotAnyOfRule" should {
    "not match" when {
      "user has one forbidden group" in {
        val ruleSettings = GroupsRulesSettings(
          permittedGroupsLogic = groupsLogicResolver(UniqueNonEmptyList.of(
            AlreadyResolved(GroupId("g1").nel),
            AlreadyResolved(GroupId("g2").nel),
          )),
          usersDefinitions = NonEmptyList.of(UserDef(
            usernames = userIdPatterns("user1"),
            mode = WithoutGroupsMapping(
              authenticationRule.matching(User.Id("user1")),
              groups("g1")
            )
          ))
        )
        val usr = Some(User.Id("user1"))
        assertNotMatchRule(
          settings = ruleSettings,
          loggedUser = usr,
          caseSensitivity = CaseSensitivity.Disabled,
          preferredGroupId = None
        )
      }
      "user has all forbidden group" in {
        val ruleSettings = GroupsRulesSettings(
          permittedGroupsLogic = groupsLogicResolver(UniqueNonEmptyList.of(
            AlreadyResolved(GroupId("g1").nel),
            AlreadyResolved(GroupId("g2").nel),
          )),
          usersDefinitions = NonEmptyList.of(UserDef(
            usernames = userIdPatterns("user1"),
            mode = WithoutGroupsMapping(
              authenticationRule.matching(User.Id("user1")),
              groups("g1", "g2")
            )
          ))
        )
        val usr = Some(User.Id("user1"))
        assertNotMatchRule(
          settings = ruleSettings,
          loggedUser = usr,
          caseSensitivity = CaseSensitivity.Disabled,
          preferredGroupId = None
        )
      }
    }

    "match" when {
      "user has none of the forbidden groups" in {
        val ruleSettings = GroupsRulesSettings(
          permittedGroupsLogic = groupsLogicResolver(UniqueNonEmptyList.of(
            AlreadyResolved(GroupId("g1").nel),
            AlreadyResolved(GroupId("g2").nel),
          )),
          usersDefinitions = NonEmptyList.of(UserDef(
            usernames = userIdPatterns("user1"),
            mode = WithoutGroupsMapping(
              authenticationRule.matching(User.Id("user1")),
              groups("h1", "h2")
            )
          ))
        )
        val usr = Some(User.Id("user1"))
        assertMatchRule(
          settings = ruleSettings,
          loggedUser = usr,
          caseSensitivity = CaseSensitivity.Disabled,
          preferredGroupId = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            user = User.Id("user1"),
            group = GroupId("h1"),
            availableGroups = UniqueList.of(group("h1"), group("h2"))
          )
        )
      }
    }
  }

}