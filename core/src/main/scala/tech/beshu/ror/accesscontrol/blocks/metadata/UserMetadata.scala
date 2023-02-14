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

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final case class UserMetadata private(loggedUser: Option[LoggedUser],
                                      currentGroup: Option[GroupName],
                                      availableGroups: UniqueList[GroupName],
                                      kibanaIndex: Option[ClusterIndexName],
                                      kibanaTemplateIndex: Option[ClusterIndexName],
                                      hiddenKibanaApps: Set[KibanaApp],
                                      kibanaAccess: Option[KibanaAccess],
                                      userOrigin: Option[UserOrigin],
                                      jwtToken: Option[JwtTokenPayload]) {

  def withLoggedUser(user: LoggedUser): UserMetadata = this.copy(loggedUser = Some(user))
  def withCurrentGroup(group: GroupName): UserMetadata = this.copy(currentGroup = Some(group))
  def addAvailableGroup(group: GroupName): UserMetadata = addAvailableGroups(UniqueNonEmptyList.of(group))
  def addAvailableGroups(groups: UniqueNonEmptyList[GroupName]): UserMetadata = {
    val newAvailableGroups = this.availableGroups.mergeWith(groups.toUniqueList)
    this.copy(
      availableGroups = newAvailableGroups,
      currentGroup = this.currentGroup.orElse(newAvailableGroups.headOption)
    )
  }
  def withAvailableGroups(groups: UniqueList[GroupName]): UserMetadata = this.copy(
    availableGroups = groups,
    currentGroup = this.currentGroup.orElse(groups.headOption)
  )
  def withKibanaIndex(index: ClusterIndexName): UserMetadata = this.copy(kibanaIndex = Some(index))
  def withKibanaTemplateIndex(index: ClusterIndexName): UserMetadata = this.copy(kibanaTemplateIndex = Some(index))
  def addHiddenKibanaApp(app: KibanaApp): UserMetadata = this.copy(hiddenKibanaApps = this.hiddenKibanaApps + app)
  def withHiddenKibanaApps(apps: NonEmptySet[KibanaApp]): UserMetadata = this.copy(hiddenKibanaApps = this.hiddenKibanaApps ++ apps.toSortedSet)
  def withKibanaAccess(access: KibanaAccess): UserMetadata = this.copy(kibanaAccess = Some(access))
  def withUserOrigin(origin: UserOrigin): UserMetadata = this.copy(userOrigin = Some(origin))
  def withJwtToken(token: JwtTokenPayload): UserMetadata = this.copy(jwtToken = Some(token))
  def clearCurrentGroup: UserMetadata = this.copy(currentGroup = None)
}

object UserMetadata {
  def from(request: RequestContext): UserMetadata = {
    request
      .headers
      .find(_.name === Header.Name.currentGroup) match {
      case None => UserMetadata.empty
      case Some(Header(_, value)) => UserMetadata.empty.withCurrentGroup(GroupName(value))
    }
  }

  def empty: UserMetadata = new UserMetadata(
    loggedUser = None,
    currentGroup = None,
    availableGroups = UniqueList.empty,
    kibanaIndex = None,
    kibanaTemplateIndex = None,
    hiddenKibanaApps = Set.empty,
    kibanaAccess = None,
    userOrigin = None,
    jwtToken = None
  )
}