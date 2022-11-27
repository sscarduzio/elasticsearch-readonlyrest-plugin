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
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.{GroupLike, GroupsLogic, PermittedGroups}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class GroupsLogicExecutorTests extends AnyWordSpec with Matchers {

  "GroupsLogic AND" should {
    "handle properly filtering of available groups from user groups" when {
      "permitted groups are all wildcarded" in {
        val and = GroupsLogic.And(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("a*"), GroupLike.from("b*")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("bca"), GroupName("cab"), GroupName("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("bca")
        )))
      }
      "permitted groups are full groups names" in {
        val and = GroupsLogic.And(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("abc"), GroupLike.from("cba")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("bca"), GroupName("cab"), GroupName("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("cba")
        )))
      }
      "permitted groups are mix of group patterns and full group names" in {
        val and = GroupsLogic.And(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("c*"), GroupLike.from("abc")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("bca"), GroupName("cab"), GroupName("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          GroupName("cab"), GroupName("cba"), GroupName("abc")
        )))
      }
      "there is one permitted group that doesn't match any of the user groups" in {
        val and = GroupsLogic.And(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("d*"), GroupLike.from("b*"), GroupLike.from("c*"), GroupLike.from("abc")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("bca"), GroupName("cab"), GroupName("cba")
        ))
        result should be(None)
      }
    }
  }

  "GroupsLogic OR" should {
    "handle properly filtering of available groups from user groups" when {
      "permitted groups are all wildcarded" in {
        val and = GroupsLogic.Or(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("a*"), GroupLike.from("b*")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("cab"), GroupName("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          GroupName("abc")
        )))
      }
      "permitted groups are full groups names" in {
        val and = GroupsLogic.Or(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("abc"), GroupLike.from("cba")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("bca"), GroupName("cab"), GroupName("cba")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          GroupName("cba")
        )))
      }
      "permitted groups are mix of group patterns and full group names" in {
        val and = GroupsLogic.Or(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("c*"), GroupLike.from("abc")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("bca"), GroupName("cab")
        ))
        result should be(Some(UniqueNonEmptyList.of(
          GroupName("cab"), GroupName("abc")
        )))
      }
      "there is none of permitted group that match any of the user groups" in {
        val and = GroupsLogic.Or(PermittedGroups(UniqueNonEmptyList.of(
          GroupLike.from("d*"), GroupLike.from("e*"), GroupLike.from("abcde")
        )))
        val result = and.availableGroupsFrom(UniqueNonEmptyList.of(
          GroupName("abc"), GroupName("bca"), GroupName("cab"), GroupName("cba")
        ))
        result should be(None)
      }
    }
  }
}