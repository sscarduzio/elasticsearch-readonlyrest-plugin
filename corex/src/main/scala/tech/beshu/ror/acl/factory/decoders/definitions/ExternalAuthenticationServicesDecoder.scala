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
package tech.beshu.ror.acl.factory.decoders.definitions

import cats.implicits._
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.{BasicAuthHttpExternalAuthenticationService, CachingExternalAuthenticationService, ExternalAuthenticationService, JwtExternalAuthenticationService}
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import tech.beshu.ror.acl.factory.decoders.common._

class ExternalAuthenticationServicesDecoder(httpClientFactory: HttpClientsFactory)
  extends DefinitionsBaseDecoder[ExternalAuthenticationService]("external_authentication_service_configs")(
    ExternalAuthenticationServicesDecoder.basicAuthExternalAuthenticationServiceDecoder(httpClientFactory)
  )

object ExternalAuthenticationServicesDecoder {

  implicit val serviceNameDecoder: Decoder[ExternalAuthenticationService.Name] =
    DecoderHelpers.decodeStringLike.map(ExternalAuthenticationService.Name.apply)

  private implicit def basicAuthExternalAuthenticationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthenticationService] = {
    cacheableAuthenticationServiceDecoder(
      (id: ExternalAuthenticationService#Id, uri: Uri, successStatusCode: Int, httpClient: HttpClient) =>
        new BasicAuthHttpExternalAuthenticationService(id, uri, successStatusCode, httpClient),
      httpClientFactory
    )
  }

  implicit def jwtExternalAuthenticationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthenticationService] = {
    cacheableAuthenticationServiceDecoder(
      (id: ExternalAuthenticationService#Id, uri: Uri, successStatusCode: Int, httpClient: HttpClient) =>
        new JwtExternalAuthenticationService(id, uri, successStatusCode, httpClient),
      httpClientFactory
    )
  }

  private def cacheableAuthenticationServiceDecoder(creator: (ExternalAuthenticationService#Id, Uri, Int, HttpClient) => ExternalAuthenticationService,
                                                    httpClientFactory: HttpClientsFactory) = {
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[ExternalAuthenticationService.Name]
          url <- c.downFields("authentication_endpoint", "url").as[Uri]
          httpSuccessCode <- c.downField("success_status_code").as[Option[Int]]
          cacheTtl <- c.downField("cache_ttl_in_sec").as[Option[FiniteDuration Refined Positive]]
          validate <- c.downField("validate").as[Option[Boolean]]
        } yield {
          val httpClient = httpClientFactory.create(Config(validate.getOrElse(defaults.validate)))
          val externalAuthService: ExternalAuthenticationService =
            creator(name, url, httpSuccessCode.getOrElse(defaults.successHttpCode), httpClient)
          cacheTtl.foldLeft(externalAuthService) {
            case (cacheableAuthService, ttl) => new CachingExternalAuthenticationService(cacheableAuthService, ttl)
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
  }

  private object defaults {
    val successHttpCode = 204
    val validate = true
  }

}
