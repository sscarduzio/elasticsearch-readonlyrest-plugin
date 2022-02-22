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
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.SupportedHttpMethod.Get
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.{AuthTokenName, AuthTokenSendMethod, QueryParam, SupportedHttpMethod}
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common.decoderTupleListDecoder
import tech.beshu.ror.accesscontrol.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

object ExternalAuthorizationServicesDecoder extends Logging {

  def instance(httpClientFactory: HttpClientsFactory): ADecoder[Id, Definitions[ExternalAuthorizationService]] = {
    implicit val serviceDecoder: SyncDecoder[ExternalAuthorizationService] = SyncDecoderCreator
      .from(ExternalAuthorizationServicesDecoder.externalAuthorizationServiceDecoder(httpClientFactory))
    DefinitionsBaseDecoder.instance[Id, ExternalAuthorizationService]("user_groups_providers")
  }

  implicit val serviceNameDecoder: Decoder[ExternalAuthorizationService.Name] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(ExternalAuthorizationService.Name.apply)

  private implicit val authTokenNameDecoder: Decoder[AuthTokenName] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(AuthTokenName.apply)

  private implicit val sendMethodDecoder: Decoder[AuthTokenSendMethod] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE[AuthTokenSendMethod] {
      case "HEADER" => Right(AuthTokenSendMethod.UsingHeader)
      case "QUERY_PARAM" => Right(AuthTokenSendMethod.UsingQueryParam)
      case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown value '$unknown' of 'auth_token_passed_as' attribute")))
    }
      .decoder

  private implicit val supportedHttpMethodDecoder: Decoder[SupportedHttpMethod] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE[SupportedHttpMethod] {
      case "POST" | "post" => Right(SupportedHttpMethod.Post)
      case "GET" | "resolve" => Right(Get)
      case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown value '$unknown' of 'http_method' attribute")))
    }
      .decoder

  private implicit val headerSetDecoder: Decoder[Set[Header]] =
    decoderTupleListDecoder.map(_.map(Header.apply).toSet)

  private implicit val queryParamSetDecoder: Decoder[Set[QueryParam]] =
    decoderTupleListDecoder.map(_.map { case (fst, snd) => QueryParam(fst, snd) }.toSet)

  private implicit def externalAuthorizationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthorizationService] = {
    SyncDecoderCreator
      .instance { c =>
        for {
          name <- c.downField("name").as[ExternalAuthorizationService.Name]
          url <- c.downFields("groups_endpoint", "url").as[Uri]
          authTokenName <- c.downField("auth_token_name").as[AuthTokenName]
          sendUsing <- c.downField("auth_token_passed_as").as[AuthTokenSendMethod]
          httpMethod <- c.downField("http_method").as[Option[SupportedHttpMethod]]
          groupsJsonPath <- c.downField("response_groups_json_path").as[JsonPath]
          defaultQueryParams <- c.downField("default_query_parameters").as[Option[Set[QueryParam]]]
          defaultHeaders <- c.downField("default_headers").as[Option[Set[Header]]]
          cacheTtl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
          validate <- c.downField("validate").as[Option[Boolean]]
          httpClientConfig <- c.downField("http_connection_settings").as[Option[HttpClientsFactory.Config]]
        } yield (name, url, authTokenName, sendUsing, httpMethod, groupsJsonPath, defaultQueryParams, defaultHeaders, cacheTtl, validate, httpClientConfig)
      }
      .emapE { case (name, url, authTokenName, sendUsing, httpMethod, groupsJsonPath, defaultQueryParams, defaultHeaders, cacheTtl, validateOpt, httpClientConfigOpt) =>
        val httpClientConfig = (validateOpt, httpClientConfigOpt) match {
          case (Some(_), Some(_)) =>
            Left(AclCreationError.RulesLevelCreationError(Message("If 'http_connection_settings' are used, 'validate' should be placed in that section")))
          case (Some(validate), None) =>
            Right(HttpClientsFactory.Config.default.copy(validate = validate))
          case (None, Some(config)) =>
            Right(config)
          case (None, None) =>
            Right(HttpClientsFactory.Config.default)
        }
        httpClientConfig.map { config =>
          val httpClient = httpClientFactory.create(config)
          val externalAuthService: ExternalAuthorizationService =
            new HttpExternalAuthorizationService(
              name,
              url,
              httpMethod.getOrElse(defaults.httpMethod),
              authTokenName,
              groupsJsonPath,
              sendUsing,
              defaultHeaders.getOrElse(Set.empty),
              defaultQueryParams.getOrElse(Set.empty),
              httpClient
            )
          cacheTtl.foldLeft(externalAuthService) {
            case (cacheableAuthService, ttl) => new CacheableExternalAuthorizationServiceDecorator(cacheableAuthService, ttl)
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
      .decoder
  }

  private object defaults {
    val httpMethod: SupportedHttpMethod = Get
    val validate = true
  }

}
