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

import cats.implicits.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Json.JsonRepresentation
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final case class UserMetadata private(loggedUser: Option[LoggedUser],
                                      currentGroupId: Option[GroupId],
                                      availableGroups: UniqueList[Group],
                                      kibanaIndex: Option[KibanaIndexName],
                                      kibanaTemplateIndex: Option[KibanaIndexName],
                                      hiddenKibanaApps: Set[KibanaApp],
                                      allowedKibanaApiPaths: Set[KibanaAllowedApiPath],
                                      kibanaAccess: Option[KibanaAccess],
                                      kibanaMetadata: Option[JsonRepresentation],
                                      userOrigin: Option[UserOrigin],
                                      jwtToken: Option[Jwt.Payload]) {

  def findCurrentGroup: Option[Group] = {
    currentGroupId
      .flatMap(groupId =>
        availableGroups.find(_.id == groupId)
      )
  }
  def withLoggedUser(user: LoggedUser): UserMetadata = this.copy(loggedUser = Some(user))
  def withCurrentGroup(group: Group): UserMetadata = withCurrentGroupId(group.id)
  def withCurrentGroupId(groupId: GroupId): UserMetadata = this.copy(currentGroupId = Some(groupId))
  def addAvailableGroup(group: Group): UserMetadata = addAvailableGroups(UniqueNonEmptyList.of(group))
  def addAvailableGroups(groups: UniqueNonEmptyList[Group]): UserMetadata = {
    val newAvailableGroups = UniqueList.from(this.availableGroups ++ groups)
    this.copy(
      availableGroups = newAvailableGroups,
      currentGroupId = this.currentGroupId.orElse(newAvailableGroups.headOption.map(_.id))
    )
  }
  def withAvailableGroups(groups: UniqueList[Group]): UserMetadata = this.copy(
    availableGroups = groups,
    currentGroupId = this.currentGroupId.orElse(groups.headOption.map(_.id))
  )
  def withKibanaIndex(index: KibanaIndexName): UserMetadata = this.copy(kibanaIndex = Some(index))
  def withKibanaTemplateIndex(index: KibanaIndexName): UserMetadata = this.copy(kibanaTemplateIndex = Some(index))
  def addHiddenKibanaApp(app: KibanaApp): UserMetadata = this.copy(hiddenKibanaApps = this.hiddenKibanaApps + app)
  def withHiddenKibanaApps(apps: UniqueNonEmptyList[KibanaApp]): UserMetadata =
    this.copy(hiddenKibanaApps = this.hiddenKibanaApps ++ apps)
  def withAllowedKibanaApiPaths(paths: UniqueNonEmptyList[KibanaAllowedApiPath]): UserMetadata =
    this.copy(allowedKibanaApiPaths = this.allowedKibanaApiPaths ++ paths)
  def withKibanaAccess(access: KibanaAccess): UserMetadata = this.copy(kibanaAccess = Some(access))
  def withKibanaMetadata(json: JsonRepresentation): UserMetadata = this.copy(kibanaMetadata = Some(json))
  def withUserOrigin(origin: UserOrigin): UserMetadata = this.copy(userOrigin = Some(origin))
  def withJwtToken(token: Jwt.Payload): UserMetadata = this.copy(jwtToken = Some(token))
  def clearCurrentGroup: UserMetadata = this.copy(currentGroupId = None)
}

object UserMetadata {
  def from(request: RequestContext): UserMetadata = {
    request
      .restRequest
      .allHeaders
      .find(_.name === Header.Name.currentGroup) match {
      case None => UserMetadata.empty
      case Some(Header(_, value)) => UserMetadata.empty.withCurrentGroupId(GroupId(value))
    }
  }

  def empty: UserMetadata = new UserMetadata(
    loggedUser = None,
    currentGroupId = None,
    availableGroups = UniqueList.empty,
    kibanaIndex = None,
    kibanaTemplateIndex = None,
    hiddenKibanaApps = Set.empty,
    allowedKibanaApiPaths = Set.empty,
    kibanaAccess = None,
    kibanaMetadata = None,
    userOrigin = None,
    jwtToken = None
  )
}