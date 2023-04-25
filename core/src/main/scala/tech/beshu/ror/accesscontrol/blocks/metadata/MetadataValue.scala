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
import tech.beshu.ror.accesscontrol.domain.Json._
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.{CorrelationId, KibanaAccess}

import scala.jdk.CollectionConverters._

sealed trait MetadataValue

object MetadataValue {

  final case class MetadataObject(value: Any) extends MetadataValue
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
      kibanaMetadata(userMetadata) ++
      userOrigin(userMetadata)
  }

  def toAny(metadataValue: MetadataValue): Any = metadataValue match {
    case MetadataObject(value) => value
    case MetadataString(value) => value: String
    case MetadataList(nel) => nel.toList.toArray: Array[String]
    case MetadataListOfMaps(listOfMaps) => listOfMaps.map(_.asJava).toList.toArray[java.util.Map[String, String]]
  }

  private def loggingId(correlationId: CorrelationId) = {
    Map("x-ror-correlation-id" -> MetadataString(correlationId.value.value))
  }

  private def userOrigin(userMetadata: UserMetadata) = {
    userMetadata.userOrigin.map(uo => ("x-ror-origin", MetadataString(uo.value.value))).toMap
  }

  private def kibanaAccess(userMetadata: UserMetadata) = {
    userMetadata.kibanaAccess.map(ka => ("x-ror-kibana_access", MetadataString(ka.show))).toMap
  }

  private def hiddenKibanaApps(userMetadata: UserMetadata) = {
    NonEmptyList
      .fromList(userMetadata.hiddenKibanaApps.toList)
      .map(apps => ("x-ror-kibana-hidden-apps", MetadataList(apps.map(_.value.value))))
      .toMap
  }

  private def kibanaApiAllowedPaths(userMetadata: UserMetadata) = {
    NonEmptyList
      .fromList(userMetadata.allowedKibanaApiPaths.toList)
      .map(paths => (
        "x-ror-kibana-allowed-api-paths",
        MetadataListOfMaps(paths.map(p => Map(
          "http_method" -> p.httpMethod.show,
          "path_regex" -> p.pathRegex.pattern.pattern()
        )))
      ))
      .toMap
  }

  private def kibanaMetadata(userMetadata: UserMetadata) = {
    userMetadata
      .kibanaMetadata
      .map(metadata => ("x-ror-kibana-metadata", MetadataObject(jsonRepresentationToJavaObj(metadata))))
      .toMap
  }

  private def jsonRepresentationToJavaObj(json: JsonRepresentation): Any = {
    json match {
      case JsonTree.Object(fields) =>
        fields.view.mapValues(jsonRepresentationToJavaObj).toMap.asJava
      case JsonTree.Array(elements) =>
        elements.map(jsonRepresentationToJavaObj).asJava
      case JsonTree.Value(value) =>
        value match {
          case JsonValue.StringValue(value) => value
          case JsonValue.NumValue(value) => value
          case JsonValue.BooleanValue(value) => value
          case JsonValue.NullValue => null
        }
    }
  }

  private def availableGroups(userMetadata: UserMetadata) = {
    NonEmptyList
      .fromList(userMetadata.availableGroups.toList)
      .map(groups => ("x-ror-available-groups", MetadataList(groups.map(_.value.value))))
      .toMap
  }

  private def foundKibanaIndex(userMetadata: UserMetadata) = {
    userMetadata.kibanaIndex.map(i => ("x-ror-kibana_index", MetadataString(i.stringify))).toMap
  }

  private def foundKibanaTemplateIndex(userMetadata: UserMetadata) = {
    userMetadata.kibanaTemplateIndex.map(i => ("x-ror-kibana_template_index", MetadataString(i.stringify))).toMap
  }

  private def currentGroup(userMetadata: UserMetadata) = {
    userMetadata.currentGroup.map(g => ("x-ror-current-group", MetadataString(g.value.value))).toMap
  }

  private def loggedUser(userMetadata: UserMetadata) = {
    userMetadata.loggedUser.map(u => ("x-ror-username", MetadataString(u.id.value.value))).toMap
  }

  private implicit val kibanaAccessShow: Show[KibanaAccess] = Show {
    case KibanaAccess.RO => "ro"
    case KibanaAccess.ROStrict => "ro_strict"
    case KibanaAccess.RW => "rw"
    case KibanaAccess.Admin => "admin"
    case KibanaAccess.ApiOnly => "api_only"
    case KibanaAccess.Unrestricted => "unrestricted"
  }

  private implicit val kibanaApiAllowedHttpMethodShow: Show[AllowedHttpMethod] = Show {
    case AllowedHttpMethod.Any => "ANY"
    case AllowedHttpMethod.Specific(httpMethod) =>
      httpMethod match {
        case HttpMethod.Get => "GET"
        case HttpMethod.Post => "POST"
        case HttpMethod.Put => "PUT"
        case HttpMethod.Delete => "DELETE"
      }
  }
}
