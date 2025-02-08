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
package tech.beshu.ror.unit.acl.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupsLogic, GroupIds}
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class GroupsLogicExecutorTests extends AnyWordSpec with Matchers {

  "GroupsLogic AND" should {
    "handle properly filtering of available groups from user groups" when {
      "permitted groups are all wildcarded" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("abc"), group("bca")
        )))
      }
      "permitted groups are full groups names" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("cba")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("abc"), group("cba")
        )))
      }
      "permitted groups are mix of group patterns and full group id" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("c*"), GroupIdLike.from("abc")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("cab"), group("cba"), group("abc")
        )))
      }
      "there is one permitted group that doesn't match any of the user groups" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("b*"), GroupIdLike.from("c*"), GroupIdLike.from("abc")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("cba")
        ))
        result should be(None)
      }
    }
  }

  "GroupsLogic OR" should {
    "handle properly filtering of available groups from user groups" when {
      "permitted groups are all wildcarded" in {
        val and = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("cab"), group("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("abc")
        )))
      }
      "permitted groups are full groups names" in {
        val and = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("cba")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("bca"), group("cab"), group("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("cba")
        )))
      }
      "permitted groups are mix of group patterns and full group ids" in {
        val and = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("c*"), GroupIdLike.from("abc")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("cab"), group("abc")
        )))
      }
      "there is none of permitted group that match any of the user groups" in {
        val and = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*"), GroupIdLike.from("abcde")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("cba")
        ))
        result should be(None)
      }
    }
  }

  "GroupsLogic NOT_ALL_OF" should {
    "handle properly filtering of available groups from user groups" when {
      "forbidden groups are all wildcarded (1)" in {
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val result = notAllOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("cab"), group("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("abc"), group("cab"), group("cba")
        )))
      }
      "forbidden groups are all wildcarded (2)" in {
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val result = notAllOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bab")
        ))
        result should be(None)
      }
      "forbidden groups are full groups names (1)" in {
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("cba")
        )))
        val result = notAllOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("bca"), group("cab"), group("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("bca"), group("cab"), group("cba")
        )))
      }
      "forbidden groups are full groups names (2)" in {
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("cba")
        )))
        val result = notAllOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("cba")
        ))
        result should be(None)
      }
      "permitted groups are mix of group patterns and full group ids" in {
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("c*"), GroupIdLike.from("abc")
        )))
        val result = notAllOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab")
        ))
        result should be(None)
      }
      "there are some, but not all of the forbidden groups present" in {
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*"), GroupIdLike.from("abcde")
        )))
        val result = notAllOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("dbc"), group("bca"), group("cab"), group("abcde")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("dbc"), group("bca"), group("cab"), group("abcde")
        )))
      }
      "there are all of the forbidden groups present" in {
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*"), GroupIdLike.from("abcde")
        )))
        val result = notAllOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("dbc"), group("eca"), group("cab"), group("abcde")
        ))
        result should be(None)
      }
    }
  }

  "GroupsLogic NOT_ANY_OF" should {
    "handle properly filtering of available groups from user groups" when {
      "forbidden groups are all wildcarded (1)" in {
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val result = notAnyOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("cab"), group("cba")
        ))
        result should be(None)
      }
      "forbidden groups are all wildcarded (2)" in {
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val result = notAnyOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("cbc"), group("dab")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("cbc"), group("dab")
        )))
      }
      "forbidden groups are full groups names (1)" in {
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("cba")
        )))
        val result = notAnyOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("bca"), group("cab"), group("cbaa")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("bca"), group("cab"), group("cbaa")
        )))
      }
      "forbidden groups are full groups names (2)" in {
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("cba")
        )))
        val result = notAnyOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab")
        ))
        result should be(None)
      }
      "permitted groups are mix of group patterns and full group ids" in {
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("c*"), GroupIdLike.from("abc")
        )))
        val result = notAnyOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab")
        ))
        result should be(None)
      }
      "there are some, but not all of the forbidden groups present" in {
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*"), GroupIdLike.from("abcde")
        )))
        val result = notAnyOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("dbc"), group("bca"), group("cab"), group("abcde")
        ))
        result should be(None)
      }
      "there are all of the forbidden groups present" in {
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*"), GroupIdLike.from("abcde")
        )))
        val result = notAnyOf.availableGroupsFrom(UniqueNonEmptyList.of(
          group("dbc"), group("eca"), group("cab"), group("abcde")
        ))
        result should be(None)
      }
    }
  }

  "GroupsLogic.Combined" should {
    "handle properly filtering of available groups from user groups" when {
      "OR rule with wildcards and NOT_ANY_OF rule with full names (1)" in {
        val or = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bac")
        )))
        val combined = GroupsLogic.Combined(or, notAnyOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("aaa"), group("bbb"), group("caa")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("aaa"), group("bbb")
        )))
      }
      "OR rule with wildcards and NOT_ANY_OF rule with full names (2)" in {
        val or = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bac")
        )))
        val combined = GroupsLogic.Combined(or, notAnyOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("aaa"), group("bbb"), group("caa"), group("abc")
        ))
        result should be(None)
      }
      "OR rule with full names and NOT_ANY_OF rule with wildcards (1)" in {
        val or = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val combined = GroupsLogic.Combined(or, notAnyOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("cab")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("cab")
        )))
      }
      "OR rule with full names and NOT_ANY_OF rule with wildcards (2)" in {
        val or = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("a*"), GroupIdLike.from("b*")
        )))
        val combined = GroupsLogic.Combined(or, notAnyOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("cab"), group("abc")
        ))
        result should be(None)
      }
      "AND rule with full names and NOT_ANY_OF rule with wildcards (1)" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*")
        )))
        val combined = GroupsLogic.Combined(and, notAnyOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"),   group("bca"), group("cab")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("abc"),   group("bca"), group("cab")
        )))
      }
      "AND rule with full names and NOT_ANY_OF rule with wildcards (2)" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAnyOf = GroupsLogic.NotAnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*")
        )))
        val combined = GroupsLogic.Combined(and, notAnyOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("eee")
        ))
        result should be(None)
      }
      "AND rule with full names and NOT_ALL_OF rule with wildcards (1)" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*")
        )))
        val combined = GroupsLogic.Combined(and, notAllOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab")
        )))
      }
      "AND rule with full names and NOT_ALL_OF rule with wildcards (2)" in {
        val and = GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*")
        )))
        val combined = GroupsLogic.Combined(and, notAllOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("eee"), group("dee")
        ))
        result should be(None)
      }
      "OR rule with full names and NOT_ALL_OF rule with wildcards (1)" in {
        val or = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*")
        )))
        val combined = GroupsLogic.Combined(or, notAllOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          group("abc"), group("bca")
        )))
      }
      "OR rule with full names and NOT_ALL_OF rule with wildcards (2)" in {
        val or = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("abc"), GroupIdLike.from("bca"), GroupIdLike.from("cab")
        )))
        val notAllOf = GroupsLogic.NotAllOf(GroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*")
        )))
        val combined = GroupsLogic.Combined(or, notAllOf)
        val result = combined.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("eee"), group("dee")
        ))
        result should be(None)
      }
    }
  }
}