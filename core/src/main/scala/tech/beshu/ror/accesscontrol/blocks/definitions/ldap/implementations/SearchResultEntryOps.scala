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

import cats.implicits._
import com.unboundid.ldap.sdk.{DN, SearchResultEntry}
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{GroupNameAttribute, GroupsFromUserEntry}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.domain.LdapGroup
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName

import scala.util.Try

object SearchResultEntryOps {

  implicit class ToLdapGroup(val entry: SearchResultEntry) extends AnyVal {
    def toLdapGroup(groupNameAttribute: GroupNameAttribute): Option[LdapGroup] = {
      for {
        groupName <- Option(entry.getAttributeValue(groupNameAttribute.value.value))
          .flatMap(NonEmptyString.unapply)
          .map(GroupName.apply)
        dn <- Option(entry.getDN).flatMap(NonEmptyString.unapply).map(Dn.apply)
      } yield LdapGroup(groupName, dn)
    }

    def toLdapGroups(mode: GroupsFromUserEntry): List[LdapGroup] = {
      for {
        groupName <- Option(entry.getAttributeValues(mode.groupsFromUserAttribute.value.value))
          .toList.flatMap(_.toList)
          .flatMap(groupNameFromDn(_, mode))
          .flatMap(NonEmptyString.unapply)
          .map(GroupName.apply)
        dn <- Option(entry.getDN).flatMap(NonEmptyString.unapply).map(Dn.apply)
      } yield LdapGroup(groupName, dn)
    }

    private def groupNameFromDn(dnString: String, mode: GroupsFromUserEntry) = {
      val dn = new DN(dnString)
      if (dn.isDescendantOf(mode.searchGroupBaseDN.value.value, false)) {
        Try {
          dn.getRDN
            .getAttributes.toList
            .filter(_.getBaseName === mode.groupNameAttribute.value.value)
            .map(_.getValue)
            .headOption
        }.toOption.flatten
      } else {
        None
      }
    }
  }
}
