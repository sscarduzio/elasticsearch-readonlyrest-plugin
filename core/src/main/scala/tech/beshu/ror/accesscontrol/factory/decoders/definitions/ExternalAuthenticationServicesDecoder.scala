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
import cats.implicits.*
import io.circe.Decoder
import io.lemonlabs.uri.Url
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

object ExternalAuthenticationServicesDecoder {

  def instance(httpClientFactory: HttpClientsFactory): ADecoder[Id, Definitions[ExternalAuthenticationService]] = {
    implicit val serviceDecoder: SyncDecoder[ExternalAuthenticationService] =
      SyncDecoderCreator.from(ExternalAuthenticationServicesDecoder.basicAuthExternalAuthenticationServiceDecoder(httpClientFactory))
    DefinitionsBaseDecoder.instance[Id, ExternalAuthenticationService]("external_authentication_service_configs")
  }

  implicit val serviceNameDecoder: Decoder[ExternalAuthenticationService.Name] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(ExternalAuthenticationService.Name.apply)

  private implicit def basicAuthExternalAuthenticationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthenticationService] = {
    cacheableAuthenticationServiceDecoder(
      (id: ExternalAuthenticationService#Id, url: Url, successStatusCode: Int, requestTimeout: PositiveFiniteDuration, httpClient: HttpClient) =>
        new BasicAuthHttpExternalAuthenticationService(id, url, successStatusCode, requestTimeout, httpClient),
      httpClientFactory
    )
  }

  implicit def jwtExternalAuthenticationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthenticationService] = {
    cacheableAuthenticationServiceDecoder(
      (id: ExternalAuthenticationService#Id, url: Url, successStatusCode: Int, requestTimeout: PositiveFiniteDuration, httpClient: HttpClient) =>
        new JwtExternalAuthenticationService(id, url, successStatusCode, requestTimeout, httpClient),
      httpClientFactory
    )
  }

  private def cacheableAuthenticationServiceDecoder(creator: (ExternalAuthenticationService#Id, Url, Int, PositiveFiniteDuration, HttpClient) => ExternalAuthenticationService,
                                                    httpClientFactory: HttpClientsFactory) = {
    SyncDecoderCreator
      .instance { c =>
        for {
          name <- c.downField("name").as[ExternalAuthenticationService.Name]
          url <- c.downFields("authentication_endpoint", "url").as[Url]
          httpSuccessCode <- c.downField("success_status_code").as[Option[Int]]
          cacheTtl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[PositiveFiniteDuration]]
          validate <- c.downField("validate").as[Option[Boolean]]
          httpClientConfig <- c.downFields("http_connection_settings").as[Option[HttpClientsFactory.Config]]
        } yield (name, url, httpSuccessCode, cacheTtl, validate, httpClientConfig)
      }
      .emapE { case (name, url, httpSuccessCode, cacheTtl, validateOpt, httpClientConfigOpt) =>
        val httpClientConfig = (validateOpt, httpClientConfigOpt) match {
          case (Some(_), Some(_)) =>
            Left(CoreCreationError.RulesLevelCreationError(Message("If 'http_connection_settings' are used, 'validate' should be placed in that section")))
          case (Some(validate), None) =>
            Right(HttpClientsFactory.Config.default.copy(validate = validate))
          case (None, Some(config)) =>
            Right(config)
          case (None, None) =>
            Right(HttpClientsFactory.Config.default)
        }
        httpClientConfig.map { config =>
          val httpClient = httpClientFactory.create(config)
          val externalAuthService: ExternalAuthenticationService =
            creator(name, url, httpSuccessCode.getOrElse(defaults.successHttpCode), config.requestTimeout, httpClient)
          cacheTtl.foldLeft(externalAuthService) {
            case (cacheableAuthService, ttl) => new CacheableExternalAuthenticationServiceDecorator(cacheableAuthService, ttl)
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
      .decoder
  }

  private object defaults {
    val successHttpCode = 204
    val validate = true
  }

}
