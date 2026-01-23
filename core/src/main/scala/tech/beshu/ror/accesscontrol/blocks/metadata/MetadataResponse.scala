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
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion

import scala.jdk.CollectionConverters.*

object MetadataResponse {

  def from(version: UserMetadataApiVersion,
           userMetadata: UserMetadata,
           currentGroupId: Option[GroupId],
           correlationId: CorrelationId): Json = {
    CurrentUserMetadataValue.from(userMetadata, correlationId, currentGroupId)
    version match {
      case UserMetadataApiVersion.V1 => CurrentUserMetadataValue.from(userMetadata, correlationId, currentGroupId)
      case UserMetadataApiVersion.V2(_) => UserMetadataValue.from(userMetadata, correlationId)
    }
  }
}

private object UserMetadataValue {

  def from(userMetadata: UserMetadata,
           correlationId: CorrelationId): Json = {
    userMetadata match {
      case UserMetadata.WithoutGroups(loggedUser, userOrigin, metadata, _) =>
        Json.obj(
          List(
            Some("type" -> Json.fromString("USER_WITHOUT_GROUPS")),
            Some("correlation_id" -> Json.fromString(correlationId.value.value)),
            Some("username" -> Json.fromString(loggedUser.id.value.value)),
            userOrigin.map(origin => "ror_origin" -> Json.fromString(origin.value.value)),
            metadata.map(m => "kibana" -> buildKibanaJson(m))
          ).flatten *
        )
      case UserMetadata.WithGroups(groupMetadata) =>
        Json.obj(
          "type" -> Json.fromString("USER_WITH_GROUPS"),
          "correlation_id" -> Json.fromString(correlationId.value.value),
          "groups" -> Json.arr(
            groupMetadata.values.map(buildGroupEntry).toSeq *
          )
        )
    }
  }

  private def buildGroupEntry(groupMetatadata: UserMetadata.WithGroups.GroupMetadata): Json = {
    Json.obj(
      List(
        Some("group" -> groupMetatadata.group.asJson),
        Some("username" -> Json.fromString(groupMetatadata.loggedUser.id.value.value)),
        groupMetatadata.userOrigin.map(origin => "ror_origin" -> Json.fromString(origin.value.value)),
        groupMetatadata.kibanaMetadata.map(m => "kibana" -> buildKibanaJson(m))
      ).flatten *
    )
  }
}

  private def buildKibanaJson(metadata: KibanaMetadata): Json = {
    Json.obj(
      List(
        Some("access" -> metadata.access.asJson),
        metadata.index.map(idx => "index" -> Json.fromString(idx.stringify)),
        metadata.templateIndex.map(idx => "template_index" -> Json.fromString(idx.stringify)),
        Option.when(metadata.hiddenApps.nonEmpty)(
          "hidden_apps" -> Json.arr(
            metadata.hiddenApps.toList.map {
              case KibanaApp.FullNameKibanaApp(name) => Json.fromString(name.value)
              case KibanaApp.KibanaAppRegex(regex) => Json.fromString(regex.value.value)
            } *
          )
        ),
        Option.when(metadata.allowedApiPaths.nonEmpty)(
          "allowed_api_paths" -> Json.arr(
            metadata.allowedApiPaths.toList.map(_.asJson) *
          )
        ),
        metadata.genericMetadata.map(json => "metadata" -> jsonRepresentationToCirceJson(json))
      ).flatten *
    )
  }

// todo: can we do it better?
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
}

// To be removed in RORDEV-1924
private object CurrentUserMetadataValue {

  def from(userMetadata: UserMetadata,
           correlationId: CorrelationId,
           currentGroupId: Option[GroupId]): Json = {
    Json.obj(
      List(
        Some("x-ror-correlation-id" -> Json.fromString(correlationId.value.value)),
        Some("x-ror-username" -> Json.fromString(loggedUser(userMetadata).id.value.value)),
        availableGroups(userMetadata),
        currentGroup(userMetadata, currentGroupId),
        kibanaAccess(userMetadata, currentGroupId),
        kibanaIndex(userMetadata, currentGroupId),
        kibanaTemplateIndex(userMetadata, currentGroupId),
        hiddenKibanaApps(userMetadata, currentGroupId),
        kibanaApiAllowedPaths(userMetadata, currentGroupId),
        kibanaGenericMetadata(userMetadata, currentGroupId),
        userOrigin(userMetadata)
      ).flatten *
    )
  }

  private def userOrigin(userMetadata: UserMetadata): Option[(String, Json)] = {
    val origin = userMetadata match {
      case UserMetadata.WithoutGroups(_, userOrigin, _, _) => userOrigin
      case UserMetadata.WithGroups(groupMetadata) => groupMetadata.values.head.userOrigin
    }
    origin.map(uo => "x-ror-origin" -> Json.fromString(uo.value.value))
  }

  private def kibanaAccess(userMetadata: UserMetadata, currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .map { kibanaMetadata => "x-ror-kibana_access" -> kibanaMetadata.access.asJson }
  }

  private def hiddenKibanaApps(userMetadata: UserMetadata, currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap { kibanaMetadata =>
        Option.when(kibanaMetadata.hiddenApps.nonEmpty) {
          "x-ror-kibana-hidden-apps" -> Json.arr(
            kibanaMetadata.hiddenApps.toList.map {
              case KibanaApp.FullNameKibanaApp(name) => Json.fromString(name.value)
              case KibanaApp.KibanaAppRegex(regex) => Json.fromString(regex.value.value)
            } *
          )
        }
      }
  }

  private def kibanaApiAllowedPaths(userMetadata: UserMetadata, currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap { kibanaMetadata =>
        Option.when(kibanaMetadata.allowedApiPaths.nonEmpty) {
          "x-ror-kibana-allowed-api-paths" -> Json.arr(
            kibanaMetadata.allowedApiPaths.toList.map(_.asJson) *
          )
        }
      }
  }

  private def kibanaGenericMetadata(userMetadata: UserMetadata, currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap(_.genericMetadata)
      .map { genericMetadata => "x-ror-kibana-metadata" -> jsonRepresentationToCirceJson(genericMetadata) }
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

  private def availableGroups(userMetadata: UserMetadata): Option[(String, Json)] = {
    userMetadata match {
      case UserMetadata.WithoutGroups(_, _, _, _) =>
        None
      case UserMetadata.WithGroups(groupMetadata) =>
        Option.when(groupMetadata.nonEmpty) {
          "x-ror-available-groups" -> Json.arr(
            groupMetadata.values.map(_.group).map(_.asJson).toSeq *
          )
        }
    }
  }

  private def kibanaIndex(userMetadata: UserMetadata, currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap(_.index)
      .map { index => "x-ror-kibana_index" -> Json.fromString(index.stringify) }
  }

  private def kibanaTemplateIndex(userMetadata: UserMetadata, currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    userMetadata
      .kibanaRelatedMetadata(currentGroupId)
      .flatMap(_.templateIndex)
      .map { index => "x-ror-kibana_template_index" -> Json.fromString(index.stringify) }
  }

  private def currentGroup(userMetadata: UserMetadata, currentGroupId: Option[GroupId]): Option[(String, Json)] = {
    (userMetadata, currentGroupId) match {
      case (UserMetadata.WithGroups(groupMetadata), Some(groupId)) =>
        groupMetadata.get(groupId).map { groupMetadata =>
          "x-ror-current-group" -> groupMetadata.group.asJson
        }
      case (UserMetadata.WithGroups(groupMetadata), None) =>
        groupMetadata.values.headOption.map { groupMetadata =>
          "x-ror-current-group" -> groupMetadata.group.asJson
        }
      case _ => None
    }
  }

  private def loggedUser(userMetadata: UserMetadata): LoggedUser = {
    userMetadata match {
      case UserMetadata.WithoutGroups(loggedUser, _, _, _) => loggedUser
      case UserMetadata.WithGroups(groupMetadata) => groupMetadata.values.head.loggedUser
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

  private implicit class UserMetadataOps(val userMetadata: UserMetadata) extends AnyVal {
    def kibanaRelatedMetadata(currentGroupId: Option[GroupId]): Option[KibanaMetadata] = {
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