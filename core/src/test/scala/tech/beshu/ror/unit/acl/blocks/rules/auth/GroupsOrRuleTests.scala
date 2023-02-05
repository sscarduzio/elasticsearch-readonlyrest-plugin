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
import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithoutGroupsMapping
import tech.beshu.ror.accesscontrol.blocks.rules.auth.BaseGroupsRule.{Settings => GroupsRuleSettings}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{BaseGroupsRule, GroupsOrRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.language.postfixOps

class GroupsOrRuleTests extends BaseGroupsRuleTests {

  override def createRule(settings: GroupsRuleSettings, caseSensitivity: UserIdCaseMappingEquality): BaseGroupsRule = {
    new GroupsOrRule(settings, caseSensitivity)
  }

  "A GroupsRule" should {
    "match" when {
      "user has not all groups" in {
        val ruleSettings = GroupsRuleSettings(
          permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(
            AlreadyResolved(GroupName("g1").nel),
            AlreadyResolved(GroupName("g2").nel),
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
        assertMatchRule(
          settings = ruleSettings,
          loggedUser = usr,
          caseSensitivity = false,
          preferredGroup = None
        )(defaultOutputBlockContextAssertion(
          user = User.Id("user1"),
          group = GroupName("g1"),
          availableGroups = UniqueList.of(GroupName("g1"))
        ))
      }
    }

    "match" when {
      "user has all groups" in {
        val ruleSettings = GroupsRuleSettings(
          permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(
            AlreadyResolved(GroupName("g1").nel),
            AlreadyResolved(GroupName("g2").nel),
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
        assertMatchRule(
          settings = ruleSettings,
          loggedUser = usr,
          caseSensitivity = false,
          preferredGroup = None
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            user = User.Id("user1"),
            group = GroupName("g1"),
            availableGroups = UniqueList.of(GroupName("g1"), GroupName("g2"))
          )
        )
      }
    }
  }
}