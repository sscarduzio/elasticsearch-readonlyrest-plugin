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

import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Json.JsonRepresentation
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final case class BlockMetadata private(loggedUser: Option[LoggedUser],
                                       currentGroupId: Option[GroupId],
                                       availableGroups: UniqueList[Group],
                                       kibanaMetadata: Option[KibanaMetadata],
                                       userOrigin: Option[UserOrigin],
                                       jwtToken: Option[Jwt.Payload]) {

  def withLoggedUser(user: LoggedUser): BlockMetadata = this.copy(loggedUser = Some(user))

  def withCurrentGroup(group: Group): BlockMetadata = withCurrentGroupId(group.id)

  def withCurrentGroupId(groupId: GroupId): BlockMetadata = this.copy(currentGroupId = Some(groupId))

  def addAvailableGroup(group: Group): BlockMetadata = addAvailableGroups(UniqueNonEmptyList.of(group))

  def addAvailableGroups(groups: UniqueNonEmptyList[Group]): BlockMetadata = {
    val newAvailableGroups = UniqueList.from(this.availableGroups ++ groups)
    this.copy(
      availableGroups = newAvailableGroups,
      currentGroupId = this.currentGroupId.orElse(newAvailableGroups.headOption.map(_.id))
    )
  }

  def withAvailableGroups(groups: UniqueList[Group]): BlockMetadata = this.copy(
    availableGroups = groups,
    currentGroupId = this.currentGroupId.orElse(groups.headOption.map(_.id))
  )

  def withKibanaIndex(index: KibanaIndexName): BlockMetadata = {
    this.copy(kibanaMetadata = Some(currentKibanaMetadataOrDefault.copy(index = Some(index))))
  }

  def withKibanaTemplateIndex(index: KibanaIndexName): BlockMetadata = {
    this.copy(kibanaMetadata = Some(currentKibanaMetadataOrDefault.copy(templateIndex = Some(index))))
  }

  def addHiddenKibanaApp(app: KibanaApp): BlockMetadata = {
    val currentKibanaMetadata = currentKibanaMetadataOrDefault
    this.copy(kibanaMetadata = Some(currentKibanaMetadataOrDefault.copy(hiddenApps = currentKibanaMetadata.hiddenApps + app)))
  }

  def withHiddenKibanaApps(apps: UniqueNonEmptyList[KibanaApp]): BlockMetadata = {
    val currentKibanaMetadata = currentKibanaMetadataOrDefault
    this.copy(kibanaMetadata = Some(currentKibanaMetadataOrDefault.copy(hiddenApps = currentKibanaMetadata.hiddenApps ++ apps)))
  }

  def withAllowedKibanaApiPaths(paths: UniqueNonEmptyList[KibanaAllowedApiPath]): BlockMetadata = {
    val currentKibanaMetadata = currentKibanaMetadataOrDefault
    this.copy(kibanaMetadata = Some(currentKibanaMetadataOrDefault.copy(allowedApiPaths = currentKibanaMetadata.allowedApiPaths ++ paths)))
  }

  def withKibanaAccess(access: KibanaAccess): BlockMetadata = {
    this.copy(kibanaMetadata = Some(currentKibanaMetadataOrDefault.copy(access = access)))
  }

  def withKibanaMetadata(json: JsonRepresentation): BlockMetadata = {
    this.copy(kibanaMetadata = Some(currentKibanaMetadataOrDefault.copy(genericMetadata = Some(json))))
  }

  def withUserOrigin(origin: UserOrigin): BlockMetadata = this.copy(userOrigin = Some(origin))

  def withJwtToken(token: Jwt.Payload): BlockMetadata = this.copy(jwtToken = Some(token))

  def clearCurrentGroup: BlockMetadata = this.copy(currentGroupId = None)

  private def currentKibanaMetadataOrDefault = kibanaMetadata.getOrElse(KibanaMetadata.default)
}

object BlockMetadata {

  def from(request: RequestContext): BlockMetadata = {
    request.currentGroupId match {
      case None => BlockMetadata.empty
      case Some(groupId) => BlockMetadata.empty.withCurrentGroupId(groupId)
    }
  }

  def empty: BlockMetadata = new BlockMetadata(
    loggedUser = None,
    currentGroupId = None,
    availableGroups = UniqueList.empty,
    kibanaMetadata = None,
    userOrigin = None,
    jwtToken = None
  )

}