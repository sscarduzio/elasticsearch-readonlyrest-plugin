package tech.beshu.ror.acl.factory.decoders.definitions

import cats.implicits._
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthenticationService, ExternalAuthenticationService, HttpExternalAuthenticationService}
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.Config
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

class ExternalAuthenticationServicesDecoder(httpClientFactory: HttpClientsFactory)
  extends DefinitionsBaseDecoder[ExternalAuthenticationService]("external_authentication_service_configs") (
    ExternalAuthenticationServicesDecoder.externalAuthenticationServiceDecoder(httpClientFactory)
  )

object ExternalAuthenticationServicesDecoder {

  implicit val serviceNameDecoder: Decoder[ExternalAuthenticationService.Name] =
    DecoderHelpers.decodeStringLike.map(ExternalAuthenticationService.Name.apply)

  private implicit def externalAuthenticationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthenticationService] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[ExternalAuthenticationService.Name]
          url <- c.downField("authentication_endpoint").as[Uri]
          httpSuccessCode <- c.downField("success_status_code").as[Option[Int]]
          cacheTtl <- c.downField("cache_ttl_in_sec").as[Option[FiniteDuration Refined Positive]]
          validate <- c.downField("validate").as[Option[Boolean]]
        } yield {
          val httpClient = httpClientFactory.create(Config(validate.getOrElse(defaults.validate)))
          val externalAuthService: ExternalAuthenticationService =
            new HttpExternalAuthenticationService(name, url, httpSuccessCode.getOrElse(defaults.successHttpCode), httpClient)
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
