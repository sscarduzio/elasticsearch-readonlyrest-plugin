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
import cats.implicits._
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.{CorrelationId, KibanaAccess}
import scala.collection.JavaConverters._

sealed trait MetadataValue

object MetadataValue {

  final case class MetadataString(value: String) extends MetadataValue
  final case class MetadataList(value: NonEmptyList[String]) extends MetadataValue
  final case class MetadataListOfMaps(value: NonEmptyList[Map[String, String]]) extends MetadataValue

  def read(userMetadata: UserMetadata,
           correlationId: CorrelationId): Map[String, MetadataValue] = {
    loggingId(correlationId) ++
      loggedUser(userMetadata) ++
      availableGroups(userMetadata) ++
      currentGroup(userMetadata) ++
      kibanaAccess(userMetadata) ++
      foundKibanaIndex(userMetadata) ++
      foundKibanaTemplateIndex(userMetadata) ++
      hiddenKibanaApps(userMetadata) ++
      kibanaApiAllowedPaths(userMetadata) ++
      userOrigin(userMetadata)
  }

  def toAny(metadataValue: MetadataValue): Any = metadataValue match {
    case MetadataString(value) => value: String
    case MetadataList(nel) => nel.toList.toArray: Array[String]
    case MetadataListOfMaps(listOfMaps) => listOfMaps.map(_.asJava).toList.toArray[java.util.Map[String, String]]
  }

  private def loggingId(correlationId: CorrelationId) = {
    Map(Constants.HEADER_CORRELATION_ID -> MetadataString(correlationId.value.value))
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

  private def kibanaApiAllowedPaths(userMetadata: UserMetadata) = {
    NonEmptyList
      .fromList(userMetadata.allowedKibanaApiPaths.toList)
      .map(paths => (
        Constants.HEADER_KIBANA_ALLOWED_API_PATHS,
        MetadataListOfMaps(paths.map(p => Map(
          Constants.HEADER_KIBANA_ALLOWED_API_HTTP_METHOD -> p.httpMethod.show,
          Constants.HEADER_KIBANA_ALLOWED_API_PATH_REGEX -> p.pathRegex.pattern.pattern()
        )))
      ))
      .toMap
  }

  private def availableGroups(userMetadata: UserMetadata) = {
    NonEmptyList
      .fromList(userMetadata.availableGroups.toList)
      .map(groups => (Constants.HEADER_GROUPS_AVAILABLE, MetadataList(groups.map(_.value.value))))
      .toMap
  }

  private def foundKibanaIndex(userMetadata: UserMetadata) = {
    userMetadata.kibanaIndex.map(i => (Constants.HEADER_KIBANA_INDEX, MetadataString(i.stringify))).toMap
  }

  private def foundKibanaTemplateIndex(userMetadata: UserMetadata) = {
    userMetadata.kibanaTemplateIndex.map(i => (Constants.HEADER_KIBANA_TEMPLATE_INDEX, MetadataString(i.stringify))).toMap
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

  private implicit val kibanaApiAllowedHttpMethodShow: Show[AllowedHttpMethod] = Show {
    case AllowedHttpMethod.Any => "ANY"
    case AllowedHttpMethod.Specific(httpMethod) =>
      httpMethod match {
        case HttpMethod.Get => "GET"
        case HttpMethod.Post => "POST"
        case HttpMethod.Put => "PUT"
        case HttpMethod.Delete => "DELTE"
      }
  }
}
