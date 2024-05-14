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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import cats.Id
import cats.implicits._
import sttp.model.Uri
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.Config._
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, Reason}
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.json.JsonPath

object ExternalAuthorizationServicesDecoder {

  def instance(httpClientFactory: HttpClientsFactory): ADecoder[Id, Definitions[ExternalAuthorizationService]] = {
    implicit val serviceDecoder: SyncDecoder[ExternalAuthorizationService] = SyncDecoderCreator
      .from(ExternalAuthorizationServicesDecoder.externalAuthorizationServiceDecoder(httpClientFactory))
    DefinitionsBaseDecoder.instance[Id, ExternalAuthorizationService]("user_groups_providers")
  }

  implicit val serviceNameDecoder: Decoder[ExternalAuthorizationService.Name] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(ExternalAuthorizationService.Name.apply)

  private implicit def externalAuthorizationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthorizationService] = {
    SyncDecoderCreator
      .instance { c =>
        for {
          name <- c.downField("name").as[ExternalAuthorizationService.Name]
          url <- c.downFields("groups_endpoint", "url").as[Uri]
          authTokenName <- c.downField("auth_token_name").as[AuthTokenName]
          sendUsing <- c.downField("auth_token_passed_as").as[AuthTokenSendMethod]
          httpMethod <- c.downField("http_method").as[Option[SupportedHttpMethod]]
          groupsConfig <- c.as[GroupsConfig](groupsConfigDecoder(name))
          defaultQueryParams <- c.downField("default_query_parameters").as[Option[Set[QueryParam]]]
          defaultHeaders <- c.downField("default_headers").as[Option[Set[Header]]]
          cacheTtl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[PositiveFiniteDuration]]
          httpClientConfig <- c.as[ValidatedHttpClientConfig].map(_.config)
        } yield {
          val httpClient = httpClientFactory.create(httpClientConfig)
          val externalAuthService: ExternalAuthorizationService =
            new HttpExternalAuthorizationService(
              id = name,
              serviceTimeout = httpClientConfig.requestTimeout,
              config = HttpExternalAuthorizationService.Config(
                uri = url,
                method = httpMethod.getOrElse(defaults.httpMethod),
                tokenName = authTokenName,
                groupsConfig = groupsConfig,
                authTokenSendMethod = sendUsing,
                defaultHeaders = defaultHeaders.getOrElse(defaults.headers),
                defaultQueryParams = defaultQueryParams.getOrElse(defaults.queryParams)
              ),
              httpClient = httpClient
            )
          cacheTtl.foldLeft(externalAuthService) {
            case (cacheableAuthService, ttl) =>
              new CacheableExternalAuthorizationServiceDecorator(cacheableAuthService, ttl)
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
      .decoder
  }

  private def groupsConfigDecoder(name: ExternalAuthorizationService.Name): Decoder[GroupsConfig] = {
    SyncDecoderCreator
      .instance { c =>
        for {
          groupIdsConfig <- c.downField("response_group_ids_json_path").as[Option[JsonPath]]
            .map(_.map(GroupsConfig.GroupIdsConfig.apply))
          groupIdsDeprecatedConfig <- c.downField("response_groups_json_path").as[Option[JsonPath]]
            .map(_.map(GroupsConfig.GroupIdsConfig.apply))
          groupNamesConfig <-
            c.downField("response_group_names_json_path").as[Option[JsonPath]]
              .map(_.map(GroupsConfig.GroupNamesConfig.apply))
        } yield (groupIdsConfig, groupIdsDeprecatedConfig, groupNamesConfig)
      }
      .emapE {
        case (Some(groupIdsConfig), None, groupNamesConfig) => Right(GroupsConfig(groupIdsConfig, groupNamesConfig))
        case (None, Some(groupIdsDeprecatedConfig), _) => Right(GroupsConfig(groupIdsDeprecatedConfig, None))
        case (None, None, _) => Left(DefinitionsLevelCreationError(Reason.Message(s"External authorization service '${name.show}' configuration is missing the 'response_group_ids_json_path' attribute")))
        case (Some(_), Some(_), _) => Left(DefinitionsLevelCreationError(Reason.Message(s"External authorization service '${name.show}' configuration cannot have the 'response_groups_json_path' and 'response_group_ids_json_path' attributes defined at the same time")))
      }
      .mapError(DefinitionsLevelCreationError.apply)
      .decoder
  }

  private implicit val authTokenNameDecoder: Decoder[AuthTokenName] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(AuthTokenName.apply)

  private implicit val sendMethodDecoder: Decoder[AuthTokenSendMethod] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE[AuthTokenSendMethod] {
        case "HEADER" => Right(AuthTokenSendMethod.UsingHeader)
        case "QUERY_PARAM" => Right(AuthTokenSendMethod.UsingQueryParam)
        case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown value '$unknown' of 'auth_token_passed_as' attribute. Supported: 'HEADER', 'QUERY_PARAM'")))
      }
      .decoder

  private implicit val supportedHttpMethodDecoder: Decoder[SupportedHttpMethod] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE[SupportedHttpMethod] {
        case "POST" | "post" => Right(SupportedHttpMethod.Post)
        case "GET" | "get" => Right(SupportedHttpMethod.Get)
        case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown value '$unknown' of 'http_method' attribute. Supported: 'GET', 'POST'")))
      }
      .decoder

  private implicit val headerSetDecoder: Decoder[Set[Header]] =
    decoderTupleListDecoder.map(_.map(Header.apply).toSet)

  private implicit val queryParamSetDecoder: Decoder[Set[QueryParam]] =
    decoderTupleListDecoder.map(_.map { case (fst, snd) => QueryParam(fst, snd) }.toSet)

  private implicit val validatedHttpClientConfigDecoder: Decoder[ValidatedHttpClientConfig] = {
    SyncDecoderCreator
      .instance { c =>
        for {
          validate <- c.downField("validate").as[Option[Boolean]]
          httpClientConfig <- c.downField("http_connection_settings").as[Option[HttpClientsFactory.Config]]
        } yield (validate, httpClientConfig)
      }
      .emapE {
        case (Some(_), Some(_)) =>
          Left(DefinitionsLevelCreationError(Message("If 'http_connection_settings' are used, 'validate' should be placed in that section")))
        case (Some(validate), None) =>
          Right(HttpClientsFactory.Config.default.copy(validate = validate))
        case (None, Some(config)) =>
          Right(config)
        case (None, None) =>
          Right(HttpClientsFactory.Config.default)
      }
      .map(ValidatedHttpClientConfig.apply)
      .mapError(DefinitionsLevelCreationError.apply)
      .decoder
  }

  private final case class ValidatedHttpClientConfig(config: HttpClientsFactory.Config)

  private object defaults {
    val httpMethod: SupportedHttpMethod = SupportedHttpMethod.Get
    val headers: Set[Header] = Set.empty
    val queryParams: Set[QueryParam] = Set.empty
  }

}
