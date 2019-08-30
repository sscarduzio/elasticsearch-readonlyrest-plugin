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
package tech.beshu.ror.es.request.usermetadata

import cats.Show
import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.common.xcontent.{ToXContent, XContentBuilder}
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.UserMetadata
import tech.beshu.ror.accesscontrol.domain.KibanaAccess

class CurrentUserMetadataResponseActionListener(baseListener: ActionListener[ActionResponse],
                                                userMetadata: UserMetadata)
  extends ActionListener[ActionResponse] {

  override def onResponse(response: ActionResponse): Unit = baseListener.onResponse(new RRMetadataResponse(userMetadata))

  override def onFailure(e: Exception): Unit = baseListener.onFailure(e)

}

private class RRMetadataResponse(userMetadata: UserMetadata)
  extends ActionResponse with ToXContent {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val sourceMap: Map[String, AnyRef] =
      userMetadata.loggedUser.map(u => (Constants.HEADER_USER_ROR, u.id.value.value)).toMap ++
        userMetadata.currentGroup.map(g => (Constants.HEADER_GROUP_CURRENT, g.value.value)).toMap ++
        userMetadata.foundKibanaIndex.map(i => (Constants.HEADER_KIBANA_INDEX, i.value.value)).toMap ++
        NonEmptyList
          .fromList(userMetadata.availableGroups.toList)
          .map(groups => (Constants.HEADER_GROUPS_AVAILABLE, groups.map(_.value.value).toList.toArray))
          .toMap ++
        stringifyKibanaAppsFrom(userMetadata).map(Constants.HEADER_KIBANA_HIDDEN_APPS -> _).toMap ++
        userMetadata.kibanaAccess.map(ka => (Constants.HEADER_KIBANA_ACCESS, ka.show)).toMap ++
        userMetadata.userOrigin.map(uo => (Constants.HEADER_USER_ORIGIN, uo.value)).toMap
    sourceMap.foreach { case (key, value) => builder.field(key, value) }
    builder
  }

  private def stringifyKibanaAppsFrom(userMetadata: UserMetadata) =
    if (userMetadata.hiddenKibanaApps.isEmpty) None
    else Some(userMetadata.hiddenKibanaApps.map(_.value.value).mkString(","))

  private implicit val kibanaAccessShow: Show[KibanaAccess] = Show {
    case KibanaAccess.RO => "ro"
    case KibanaAccess.ROStrict => "ro_strict"
    case KibanaAccess.RW => "rw"
    case KibanaAccess.Admin => "admin"
  }
}

