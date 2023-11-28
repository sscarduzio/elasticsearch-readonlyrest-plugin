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

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupsLogic, PermittedGroupIds}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList
import tech.beshu.ror.utils.TestsUtils.group

class GroupsLogicExecutorTests extends AnyWordSpec with Matchers {

  "GroupsLogic AND" should {
    "handle properly filtering of available groups from user groups" when {
      "permitted groups are all wildcarded" in {
        val and = GroupsLogic.And(PermittedGroupIds(UniqueNonEmptyList.of(
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
        val and = GroupsLogic.And(PermittedGroupIds(UniqueNonEmptyList.of(
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
        val and = GroupsLogic.And(PermittedGroupIds(UniqueNonEmptyList.of(
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
        val and = GroupsLogic.And(PermittedGroupIds(UniqueNonEmptyList.of(
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
        val and = GroupsLogic.Or(PermittedGroupIds(UniqueNonEmptyList.of(
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
        val and = GroupsLogic.Or(PermittedGroupIds(UniqueNonEmptyList.of(
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
        val and = GroupsLogic.Or(PermittedGroupIds(UniqueNonEmptyList.of(
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
        val and = GroupsLogic.Or(PermittedGroupIds(UniqueNonEmptyList.of(
          GroupIdLike.from("d*"), GroupIdLike.from("e*"), GroupIdLike.from("abcde")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          group("abc"), group("bca"), group("cab"), group("cba")
        ))
        result should be(None)
      }
    }
  }
}