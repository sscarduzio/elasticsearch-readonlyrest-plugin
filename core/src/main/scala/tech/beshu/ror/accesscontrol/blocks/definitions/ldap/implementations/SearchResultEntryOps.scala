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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import cats.implicits.*
import com.unboundid.ldap.sdk.{DN, SearchResultEntry}
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{GroupAttribute, GroupsFromUserEntry}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.domain.LdapGroup
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, GroupName}

import scala.util.Try

private [implementations] object SearchResultEntryOps {

  implicit class ToLdapGroup(val entry: SearchResultEntry) extends AnyVal {
    def toLdapGroup(groupAttribute: GroupAttribute): Option[LdapGroup] = {
      for {
        groupId <- Option(entry.getAttributeValue(groupAttribute.id.value.value))
          .flatMap(NonEmptyString.unapply)
          .map(GroupId.apply)
        groupName <- Option(entry.getAttributeValue(groupAttribute.name.value.value))
          .flatMap(NonEmptyString.unapply)
          .map(GroupName.apply)
        dn <- Option(entry.getDN).flatMap(NonEmptyString.unapply).map(Dn.apply)
      } yield LdapGroup(Group(groupId, groupName), dn)
    }

    def toLdapGroups(mode: GroupsFromUserEntry): List[LdapGroup] = {
      Option(entry.getAttributeValues(mode.groupsFromUserAttribute.value.value))
        .toList.flatMap(_.toList)
        .flatMap(NonEmptyString.unapply)
        .flatMap(ldapGroupFromDn(_, mode))
    }

    private def ldapGroupFromDn(dnString: NonEmptyString, mode: GroupsFromUserEntry) = {
      val dn = new DN(dnString.value)
      if (dn.isDescendantOf(mode.searchGroupBaseDN.value.value, false)) {
        Try {
          dn.getRDN
            .getAttributes.toList
            .filter(_.getBaseName === mode.groupIdAttribute.value.value)
            .map(_.getValue)
            .headOption
        }.toOption.flatten.flatMap(NonEmptyString.unapply)
          .map { groupId => LdapGroup(Group.from(GroupId(groupId)), Dn(dnString)) }
      } else {
        None
      }
    }
  }
}
