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
package tech.beshu.ror.accesscontrol.blocks.metadata

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.blocks.Block.Policy
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.WithGroups.GroupMetadata
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, UserOrigin}
import tech.beshu.ror.utils.NonEmptyListMap

sealed trait UserMetadata
object UserMetadata {

  final case class WithoutGroups(loggedUser: LoggedUser,
                                 userOrigin: Option[UserOrigin],
                                 kibanaPolicy: Option[KibanaPolicy],
                                 metadataOrigin: MetadataOrigin)
    extends UserMetadata

  final case class WithGroups private(groupsMetadata: NonEmptyListMap[GroupId, GroupMetadata])
    extends UserMetadata
  object WithGroups {
    def apply(groupsMetadata: NonEmptyList[GroupMetadata]): WithGroups = WithGroups {
      NonEmptyListMap.from(groupsMetadata.map(elem => (elem.group.id, elem)))
    }

    final case class GroupMetadata(group: Group,
                                   loggedUser: LoggedUser,
                                   userOrigin: Option[UserOrigin],
                                   kibanaPolicy: Option[KibanaPolicy],
                                   // todo: try to get rid of the metadata origin in the future from this place
                                   metadataOrigin: MetadataOrigin)


    extension (groupMetadata: GroupMetadata) {
      def isAllowed: Boolean = {
        groupMetadata.metadataOrigin.blockContext.block.policy == Policy.Allow
      }
    }

    extension (withGroups: WithGroups) {
      def excludeOtherThanAllowTypeGroups(): Option[WithGroups] = {
        NonEmptyList
          .fromList {
            withGroups
              .groupsMetadata.values
              .filter(_.metadataOrigin.blockContext.block.policy == Policy.Allow)
              .toList
          }
          .map(WithGroups.apply)
      }
    }
  }

  final case class MetadataOrigin(blockContext: UserMetadataRequestBlockContext)
}