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
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.WithGroups.GroupMetadata
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Json.*
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.utils.CirceOps.toJava

object MetadataResponse {

  type JavaJsonObject = java.util.Map[String, Object]

  def fromAsJavaJsonObject(version: UserMetadataApiVersion,
                           userMetadata: UserMetadata,
                           currentGroupId: Option[GroupId],
                           correlationId: CorrelationId): JavaJsonObject = {
    val json = fromAsCirceJson(version, userMetadata, currentGroupId, correlationId)
    if (json.isObject) {
      json.toJava.asInstanceOf[JavaJsonObject]
    } else {
      throw new IllegalStateException("Response metadata must be a JSON object.")
    }
  }

  def fromAsCirceJson(version: UserMetadataApiVersion,
                      userMetadata: UserMetadata,
                      currentGroupId: Option[GroupId],
                      correlationId: CorrelationId): Json = {
    version match {
      case UserMetadataApiVersion.V1 => CurrentUserMetadataValue.from(userMetadata, correlationId, currentGroupId)
      case UserMetadataApiVersion.V2(licenseType) => UserMetadataValue.from(userMetadata, correlationId, licenseType)
    }
  }
}

private object UserMetadataValue {

  def from(userMetadata: UserMetadata,
           correlationId: CorrelationId,
           licenseType: RorKbnLicenseType): Json = {
    implicit val licenseTypeImplicit: RorKbnLicenseType = licenseType
    userMetadata match {
      case withoutGroups: UserMetadata.WithoutGroups =>
        implicit val encoder: Encoder[UserMetadata.WithoutGroups] = withoutGroupsEncoder(correlationId)
        withoutGroups.asJson.deepDropNullValues
      case withGroups: UserMetadata.WithGroups =>
        implicit val encoder: Encoder[UserMetadata.WithGroups] = withGroupsEncoder(correlationId)
        withGroups.asJson.deepDropNullValues
    }
  }

  private def withoutGroupsEncoder(correlationId: CorrelationId)
                                  (implicit licenseType: RorKbnLicenseType): Encoder[UserMetadata.WithoutGroups] =
    Encoder.forProduct5("type", "correlation_id", "username", "ror_origin", "kibana") { userMetadata =>
      (
        "USER_WITHOUT_GROUPS",
        correlationId,
        userMetadata.loggedUser,
        userMetadata.userOrigin,
        userMetadata.kibanaPolicy
      )
    }

  private def withGroupsEncoder(correlationId: CorrelationId)
                               (implicit licenseType: RorKbnLicenseType): Encoder[UserMetadata.WithGroups] =
    Encoder.forProduct3("type", "correlation_id", "groups") { userMetadata =>
      (
        "USER_WITH_GROUPS",
        correlationId,
        userMetadata.groupsMetadata.values
      )
    }

  private implicit def groupMetadataEncoder(implicit licenseType: RorKbnLicenseType): Encoder[GroupMetadata] = {
    Encoder
      .forProduct4("group", "username", "ror_origin", "kibana") { groupMetadata =>
        (
          groupMetadata.group,
          groupMetadata.loggedUser,
          groupMetadata.userOrigin,
          groupMetadata.kibanaPolicy
        )
      }
  }

  private implicit def kibanaPolicyEncoder(implicit licenseType: RorKbnLicenseType): Encoder[KibanaPolicy] =
    Encoder
      .forProduct6("access", "index", "template_index", "hidden_apps", "allowed_api_paths", "metadata") { policy =>
        (
          policy.access,
          policy.index,
          if (licenseType.isEnterprise) policy.templateIndex else None,
          if (licenseType.isProOrEnterprise) NonEmptyList.fromList(policy.hiddenApps.toList) else None,
          NonEmptyList.fromList(policy.allowedApiPaths.toList),
          if (licenseType.isEnterprise) policy.genericMetadata else None
        )
      }

  private implicit lazy val correlationIdEncoder: Encoder[CorrelationId] = Encoder.encodeString.contramap(_.value.value)

  private implicit lazy val loggedUserEncoder: Encoder[LoggedUser] = Encoder.encodeString.contramap(_.id.value.value)

  private implicit lazy val userOriginEncoder: Encoder[UserOrigin] = Encoder.encodeString.contramap(_.value.value)

  private implicit lazy val kibanaIndexNameEncoder: Encoder[KibanaIndexName] = Encoder.encodeString.contramap(_.stringify)

  private implicit lazy val kibanaAppEncoder: Encoder[KibanaApp] = Encoder.encodeString.contramap {
    case KibanaApp.FullNameKibanaApp(name) => name.value
    case KibanaApp.KibanaAppRegex(regex) => regex.value.value
  }

  private implicit lazy val jsonRepresentationEncoder: Encoder[JsonRepresentation] = Encoder.instance { json =>
    def convert(j: JsonRepresentation): Json = j match {
      case JsonTree.Object(fields) =>
        Json.obj(fields.view.mapValues(convert).toSeq *)
      case JsonTree.Array(elements) =>
        Json.arr(elements.map(convert) *)
      case JsonTree.Value(value) =>
        value match {
          case JsonValue.StringValue(v) => Json.fromString(v)
          case JsonValue.NumValue(v) => Json.fromBigDecimal(v)
          case JsonValue.BooleanValue(v) => Json.fromBoolean(v)
          case JsonValue.NullValue => Json.Null
        }
    }

    convert(json)
  }

  private implicit lazy val groupEncoder: Encoder[Group] =
    Encoder.forProduct2("id", "name")(g => (g.id.value.value, g.name.value.value))

  private implicit lazy val kibanaAccessEncoder: Encoder[KibanaAccess] = Encoder.encodeString.contramap {
    case KibanaAccess.RO => "ro"
    case KibanaAccess.ROStrict => "ro_strict"
    case KibanaAccess.RW => "rw"
    case KibanaAccess.Admin => "admin"
    case KibanaAccess.ApiOnly => "api_only"
    case KibanaAccess.Unrestricted => "unrestricted"
  }

  private implicit lazy val allowedHttpMethodEncoder: Encoder[AllowedHttpMethod] = Encoder.encodeString.contramap {
    case AllowedHttpMethod.Any => "ANY"
    case AllowedHttpMethod.Specific(httpMethod) =>
      httpMethod match {
        case HttpMethod.Get => "GET"
        case HttpMethod.Post => "POST"
        case HttpMethod.Put => "PUT"
        case HttpMethod.Delete => "DELETE"
      }
  }

  private implicit lazy val kibanaAllowedApiPathEncoder: Encoder[KibanaAllowedApiPath] =
    Encoder.forProduct2("http_method", "path_regex")(k => (k.httpMethod, k.pathRegex.pattern.pattern()))
}

// To be removed in RORDEV-1924
private object CurrentUserMetadataValue {

  def from(userMetadata: UserMetadata,
           correlationId: CorrelationId,
           currentGroupId: Option[GroupId]): Json = {
    implicit val encoder: Encoder[UserMetadata] = userMetadataEncoder(correlationId, currentGroupId)
    userMetadata.asJson.deepDropNullValues
  }

  private def userMetadataEncoder(correlationId: CorrelationId,
                                  currentGroupId: Option[GroupId]): Encoder[UserMetadata] = Encoder
    .forProduct11(
      "x-ror-correlation-id",
      "x-ror-username",
      "x-ror-available-groups",
      "x-ror-current-group",
      "x-ror-kibana_access",
      "x-ror-kibana_index",
      "x-ror-kibana_template_index",
      "x-ror-kibana-hidden-apps",
      "x-ror-kibana-allowed-api-paths",
      "x-ror-kibana-metadata",
      "x-ror-origin"
    ) { userMetadata =>
      (
        correlationId,
        userMetadata.loggedUser,
        userMetadata.availableGroups,
        userMetadata.currentGroupBy(currentGroupId),
        userMetadata.kibanaAccessBy(currentGroupId),
        userMetadata.kibanaIndexBy(currentGroupId),
        userMetadata.kibanaTemplateIndexBy(currentGroupId),
        userMetadata.hiddenAppsBy(currentGroupId),
        userMetadata.allowedApiPathsBy(currentGroupId),
        userMetadata.genericKibanaMetadataBy(currentGroupId),
        userMetadata.userOrigin
      )
    }

  private implicit lazy val correlationIdEncoder: Encoder[CorrelationId] = Encoder.encodeString.contramap(_.value.value)

  private implicit lazy val loggedUserEncoder: Encoder[LoggedUser] = Encoder.encodeString.contramap(_.id.value.value)

  private implicit lazy val kibanaIndexNameEncoder: Encoder[KibanaIndexName] = Encoder.encodeString.contramap(_.stringify)

  private implicit lazy val kibanaAppEncoder: Encoder[KibanaApp] = Encoder.encodeString.contramap {
    case KibanaApp.FullNameKibanaApp(name) => name.value
    case KibanaApp.KibanaAppRegex(regex) => regex.value.value
  }

  private implicit lazy val jsonRepresentationEncoder: Encoder[JsonRepresentation] = Encoder.instance { json =>
    def convert(j: JsonRepresentation): Json = j match {
      case JsonTree.Object(fields) =>
        Json.obj(fields.view.mapValues(convert).toSeq *)
      case JsonTree.Array(elements) =>
        Json.arr(elements.map(convert) *)
      case JsonTree.Value(value) =>
        value match {
          case JsonValue.StringValue(v) => Json.fromString(v)
          case JsonValue.NumValue(v) => Json.fromBigDecimal(v)
          case JsonValue.BooleanValue(v) => Json.fromBoolean(v)
          case JsonValue.NullValue => Json.Null
        }
    }

    convert(json)
  }

  private implicit lazy val userOriginEncoder: Encoder[UserOrigin] = Encoder.encodeString.contramap(_.value.value)

  private implicit lazy val groupEncoder: Encoder[Group] =
    Encoder.forProduct2("id", "name")(g => (g.id.value.value, g.name.value.value))

  private implicit lazy val kibanaAllowedApiPathEncoder: Encoder[KibanaAllowedApiPath] =
    Encoder.forProduct2("http_method", "path_regex")(k => (k.httpMethod, k.pathRegex.pattern.pattern()))

  private implicit lazy val kibanaAccessEncoder: Encoder[KibanaAccess] = Encoder.encodeString.contramap {
    case KibanaAccess.RO => "ro"
    case KibanaAccess.ROStrict => "ro_strict"
    case KibanaAccess.RW => "rw"
    case KibanaAccess.Admin => "admin"
    case KibanaAccess.ApiOnly => "api_only"
    case KibanaAccess.Unrestricted => "unrestricted"
  }

  private implicit lazy val allowedHttpMethodEncoder: Encoder[AllowedHttpMethod] = Encoder.encodeString.contramap {
    case AllowedHttpMethod.Any => "ANY"
    case AllowedHttpMethod.Specific(httpMethod) =>
      httpMethod match {
        case HttpMethod.Get => "GET"
        case HttpMethod.Post => "POST"
        case HttpMethod.Put => "PUT"
        case HttpMethod.Delete => "DELETE"
      }
  }

  extension (userMetadata: UserMetadata) {

    private def loggedUser: LoggedUser = {
      userMetadata match {
        case UserMetadata.WithoutGroups(loggedUser, _, _, _) => loggedUser
        case UserMetadata.WithGroups(groupsMetadata) => groupsMetadata.values.head.loggedUser
      }
    }

    private def availableGroups: Option[NonEmptyList[Group]] = {
      NonEmptyList.fromList {
        userMetadata match {
          case UserMetadata.WithoutGroups(_, _, _, _) => List.empty
          case UserMetadata.WithGroups(groupsMetadata) => groupsMetadata.values.map(_.group).toList
        }
      }
    }

    private def currentGroupBy(groupId: Option[GroupId]): Option[Group] = {
      (userMetadata, groupId) match {
        case (UserMetadata.WithGroups(groupsMetadata), Some(groupId)) =>
          groupsMetadata.get(groupId).map(_.group)
        case (UserMetadata.WithGroups(groupsMetadata), None) =>
          Some(groupsMetadata.values.head.group)
        case _ =>
          None
      }
    }

    private def kibanaAccessBy(groupId: Option[GroupId]): Option[KibanaAccess] = {
      kibanaPolicyBy(groupId).map(_.access)
    }

    private def kibanaIndexBy(groupId: Option[GroupId]): Option[KibanaIndexName] = {
      kibanaPolicyBy(groupId).flatMap(_.index)
    }

    private def kibanaTemplateIndexBy(groupId: Option[GroupId]): Option[KibanaIndexName] = {
      kibanaPolicyBy(groupId).flatMap(_.templateIndex)
    }

    private def hiddenAppsBy(groupId: Option[GroupId]): Option[NonEmptyList[KibanaApp]] = {
      NonEmptyList.fromList(kibanaPolicyBy(groupId).toList.flatMap(_.hiddenApps))
    }

    private def allowedApiPathsBy(groupId: Option[GroupId]): Option[NonEmptyList[KibanaAllowedApiPath]] = {
      NonEmptyList.fromList(kibanaPolicyBy(groupId).toList.flatMap(_.allowedApiPaths))
    }

    private def genericKibanaMetadataBy(groupId: Option[GroupId]): Option[JsonRepresentation] = {
      kibanaPolicyBy(groupId).flatMap(_.genericMetadata)
    }

    private def userOrigin: Option[UserOrigin] = {
      userMetadata match {
        case UserMetadata.WithoutGroups(_, userOrigin, _, _) => userOrigin
        case UserMetadata.WithGroups(groupsMetadata) => groupsMetadata.values.head.userOrigin
      }
    }

    private def kibanaPolicyBy(currentGroupId: Option[GroupId]): Option[KibanaPolicy] = {
      userMetadata match {
        case UserMetadata.WithoutGroups(_, _, metadata, _) => metadata
        case UserMetadata.WithGroups(groupsMetadata) =>
          currentGroupId match {
            case Some(id) => groupsMetadata.get(id).flatMap(_.kibanaPolicy)
            case None => groupsMetadata.values.head.kibanaPolicy
          }
      }
    }
  }
}
