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

import io.circe.syntax.*
import io.circe.{Encoder, Json}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Json.*
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod

object MetadataResponse {

  def from(userMetadata: UserMetadata,
           currentGroupId: Option[GroupId],
           correlationId: CorrelationId): Json = {
    CurrentUserMetadataValue.from(userMetadata, correlationId, currentGroupId)
  }
}

private object CurrentUserMetadataValue {

  def from(userMetadata: UserMetadata,
           correlationId: CorrelationId,
           currentGroupId: Option[GroupId]): Json = {
    Json.obj(
      List(
        Some("x-ror-correlation-id" -> Json.fromString(correlationId.value.value)),
        loggedUser("x-ror-username", userMetadata),
        availableGroups("x-ror-available-groups", userMetadata),
        currentGroup("x-ror-current-group", userMetadata, currentGroupId),
        kibanaAccess("x-ror-kibana_access", userMetadata, currentGroupId),
        kibanaIndex("x-ror-kibana_index", userMetadata, currentGroupId),
        kibanaTemplateIndex("x-ror-kibana_template_index", userMetadata, currentGroupId),
        hiddenKibanaApps("x-ror-kibana-hidden-apps", userMetadata, currentGroupId),
        kibanaApiAllowedPaths("x-ror-kibana-allowed-api-paths", userMetadata, currentGroupId),
        kibanaGenericMetadata("x-ror-kibana-metadata", userMetadata, currentGroupId),
        userOrigin("x-ror-origin", userMetadata)
      ).flatten *
    )
  }

  private def userOrigin(fieldName: String, userMetadata: UserMetadata): Option[(String, Json)] = {
    val origin = userMetadata match {
      case UserMetadata.WithoutGroups(_, userOrigin, _, _) => userOrigin
      case UserMetadata.WithGroups(groupMetadata) => groupMetadata.values.head.userOrigin
    }
    origin.map(uo => fieldName -> Json.fromString(uo.value.value))
  }

  private def kibanaAccess(fieldName: String,
                           userMetadata: UserMetadata,
                           currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .map { kibanaMetadata => fieldName -> kibanaMetadata.access.asJson }
  }

  private def hiddenKibanaApps(fieldName: String,
                               userMetadata: UserMetadata,
                               currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap { kibanaMetadata =>
        Option.when(kibanaMetadata.hiddenApps.nonEmpty) {
          fieldName -> Json.arr(
            kibanaMetadata.hiddenApps.toList.map {
              case KibanaApp.FullNameKibanaApp(name) => Json.fromString(name.value)
              case KibanaApp.KibanaAppRegex(regex) => Json.fromString(regex.value.value)
            } *
          )
        }
      }
  }

  private def kibanaApiAllowedPaths(fieldName: String,
                                    userMetadata: UserMetadata,
                                    currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap { kibanaMetadata =>
        Option.when(kibanaMetadata.allowedApiPaths.nonEmpty) {
          fieldName -> Json.arr(kibanaMetadata.allowedApiPaths.toList.map(_.asJson) *)
        }
      }
  }

  private def kibanaGenericMetadata(fieldName: String,
                                    userMetadata: UserMetadata,
                                    currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap(_.genericMetadata)
      .map { genericMetadata => fieldName -> jsonRepresentationToCirceJson(genericMetadata) }
  }

  private def jsonRepresentationToCirceJson(json: JsonRepresentation): Json = {
    json match {
      case JsonTree.Object(fields) =>
        Json.obj(fields.view.mapValues(jsonRepresentationToCirceJson).toSeq *)
      case JsonTree.Array(elements) =>
        Json.arr(elements.map(jsonRepresentationToCirceJson) *)
      case JsonTree.Value(value) =>
        value match {
          case JsonValue.StringValue(v) => Json.fromString(v)
          case JsonValue.NumValue(v) => Json.fromBigDecimal(v)
          case JsonValue.BooleanValue(v) => Json.fromBoolean(v)
          case JsonValue.NullValue => Json.Null
        }
    }
  }

  private def loggedUser(fieldName: String, userMetadata: UserMetadata) = {
    val user = userMetadata match {
      case UserMetadata.WithoutGroups(loggedUser, _, _, _) => loggedUser
      case UserMetadata.WithGroups(groupMetadata) => groupMetadata.values.head.loggedUser
    }
    Some(fieldName -> Json.fromString(user.id.value.value))
  }

  private def availableGroups(fieldName: String, userMetadata: UserMetadata): Option[(String, Json)] = {
    userMetadata match {
      case UserMetadata.WithoutGroups(_, _, _, _) =>
        None
      case UserMetadata.WithGroups(groupMetadata) =>
        Option.when(groupMetadata.nonEmpty) {
          fieldName -> Json.arr(groupMetadata.values.map(_.group).map(_.asJson).toSeq *)
        }
    }
  }

  private def kibanaIndex(fieldName: String,
                          userMetadata: UserMetadata,
                          currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap(_.index)
      .map { index => fieldName -> Json.fromString(index.stringify) }
  }

  private def kibanaTemplateIndex(fieldName: String,
                                  userMetadata: UserMetadata,
                                  currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap(_.templateIndex)
      .map { index => fieldName -> Json.fromString(index.stringify) }
  }

  private def currentGroup(fieldName: String,
                           userMetadata: UserMetadata,
                           currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    (userMetadata, currentGroupId) match {
      case (UserMetadata.WithGroups(groupMetadata), Some(groupId)) =>
        groupMetadata.get(groupId).map { groupMetadata =>
          fieldName -> groupMetadata.group.asJson
        }
      case (UserMetadata.WithGroups(groupMetadata), None) =>
        groupMetadata.values.headOption.map { groupMetadata =>
          fieldName -> groupMetadata.group.asJson
        }
      case _ => None
    }
  }

  private implicit val kibanaAccessEncoder: Encoder[KibanaAccess] = Encoder.encodeString.contramap {
    case KibanaAccess.RO => "ro"
    case KibanaAccess.ROStrict => "ro_strict"
    case KibanaAccess.RW => "rw"
    case KibanaAccess.Admin => "admin"
    case KibanaAccess.ApiOnly => "api_only"
    case KibanaAccess.Unrestricted => "unrestricted"
  }

  private implicit val allowedHttpMethodEncoder: Encoder[AllowedHttpMethod] = Encoder.encodeString.contramap {
    case AllowedHttpMethod.Any => "ANY"
    case AllowedHttpMethod.Specific(httpMethod) =>
      httpMethod match {
        case HttpMethod.Get => "GET"
        case HttpMethod.Post => "POST"
        case HttpMethod.Put => "PUT"
        case HttpMethod.Delete => "DELETE"
      }
  }

  private implicit val kibanaAllowedApiPathEncoder: Encoder[KibanaAllowedApiPath] = Encoder.instance { path =>
    Json.obj(
      "http_method" -> path.httpMethod.asJson,
      "path_regex" -> Json.fromString(path.pathRegex.pattern.pattern())
    )
  }

  private implicit val groupEncoder: Encoder[Group] = Encoder.instance { group =>
    Json.obj(
      "id" -> Json.fromString(group.id.value.value),
      "name" -> Json.fromString(group.name.value.value)
    )
  }

  extension (userMetadata: UserMetadata) {
    private def kibanaRelatedMetadata(currentGroupId: Option[GroupId]): Option[KibanaMetadata] = {
      userMetadata match {
        case UserMetadata.WithoutGroups(_, _, metadata, _) => metadata
        case UserMetadata.WithGroups(groupMetadata) =>
          currentGroupId match {
            case Some(id) => groupMetadata.get(id).flatMap(_.kibanaMetadata)
            case None => groupMetadata.values.head.kibanaMetadata
          }
      }
    }
  }
}