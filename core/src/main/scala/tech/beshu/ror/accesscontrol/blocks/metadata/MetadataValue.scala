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

import cats.Show
import cats.data.NonEmptyList
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.KibanaAccess
import cats.implicits._

sealed trait MetadataValue

object MetadataValue {

  final case class MetadataString(value: String) extends MetadataValue
  final case class MetadataList(value: NonEmptyList[String]) extends MetadataValue
  def read(userMetadata: UserMetadata): Map[String, MetadataValue] = {
    loggedUser(userMetadata) ++
      currentGroup(userMetadata) ++
      foundKibanaIndex(userMetadata) ++
      foundKibanaTemplateIndex(userMetadata) ++
      availableGroups(userMetadata) ++
      hiddenKibanaApps(userMetadata) ++
      kibanaAccess(userMetadata) ++
      userOrigin(userMetadata)
  }

  def toAny(metadataValue: MetadataValue): Any = metadataValue match {
    case MetadataString(value) => value: String
    case MetadataList(nel) => nel.toList.toArray: Array[String]
  }

  private def userOrigin(userMetadata: UserMetadata) = {
    userMetadata.userOrigin.map(uo => (Constants.HEADER_USER_ORIGIN, MetadataString(uo.value.value))).toMap
  }

  private def kibanaAccess(userMetadata: UserMetadata) = {
    userMetadata.kibanaAccess.map(ka => (Constants.HEADER_KIBANA_ACCESS, MetadataString(ka.show))).toMap
  }

  private def hiddenKibanaApps(userMetadata: UserMetadata) = {
    NonEmptyList
      .fromList(userMetadata.hiddenKibanaApps.toList)
      .map(apps => (Constants.HEADER_KIBANA_HIDDEN_APPS, MetadataList(apps.map(_.value.value))))
      .toMap
  }

  private def availableGroups(userMetadata: UserMetadata) = {
    NonEmptyList
      .fromList(userMetadata.availableGroups.toList)
      .map(groups => (Constants.HEADER_GROUPS_AVAILABLE, MetadataList(groups.map(_.value.value))))
      .toMap
  }

  private def foundKibanaIndex(userMetadata: UserMetadata) = {
    userMetadata.kibanaIndex.map(i => (Constants.HEADER_KIBANA_INDEX, MetadataString(i.value.value))).toMap
  }

  private def foundKibanaTemplateIndex(userMetadata: UserMetadata) = {
    userMetadata.kibanaTemplateIndex.map(i => (Constants.HEADER_KIBANA_TEMPLATE_INDEX, MetadataString(i.value.value))).toMap
  }

  private def currentGroup(userMetadata: UserMetadata) = {
    userMetadata.currentGroup.map(g => (Constants.HEADER_GROUP_CURRENT, MetadataString(g.value.value))).toMap
  }

  private def loggedUser(userMetadata: UserMetadata) = {
    userMetadata.loggedUser.map(u => (Constants.HEADER_USER_ROR, MetadataString(u.id.value.value))).toMap
  }

  private implicit val kibanaAccessShow: Show[KibanaAccess] = Show {
    case KibanaAccess.RO => "ro"
    case KibanaAccess.ROStrict => "ro_strict"
    case KibanaAccess.RW => "rw"
    case KibanaAccess.Admin => "admin"
    case KibanaAccess.Unrestricted => "unrestricted"
  }
}
