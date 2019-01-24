package tech.beshu.ror.acl.factory.decoders.definitions

import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.Config
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, _}

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

class ExternalAuthorizationServicesDecoder(httpClientFactory: HttpClientsFactory)
  extends DefinitionsBaseDecoder[ExternalAuthorizationService]("user_groups_providers") (
    ExternalAuthorizationServicesDecoder.externalAuthorizationServiceDecoder(httpClientFactory)
  )

object ExternalAuthorizationServicesDecoder {

  implicit val serviceNameDecoder: Decoder[ExternalAuthorizationService.Name] =
    DecoderHelpers.decodeStringLike.map(ExternalAuthorizationService.Name.apply)

  private implicit def externalAuthorizationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthorizationService] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[ExternalAuthorizationService.Name]
          url <- c.downField("groups_endpoint").as[Uri]
          httpSuccessCode <- c.downField("success_status_code").as[Option[Int]]
          cacheTtl <- c.downField("cache_ttl_in_sec").as[Option[FiniteDuration Refined Positive]]
          validate <- c.downField("validate").as[Option[Boolean]]
        } yield {
          val httpClient = httpClientFactory.create(Config(validate.getOrElse(defaults.validate)))
          //todo: finish
//          val externalAuthService: ExternalAuthorizationService =
//            new HttpExternalAuthorizationService(name, url, httpSuccessCode.getOrElse(defaults.successHttpCode), httpClient)
//          cacheTtl.foldLeft(externalAuthService) {
//            case (cacheableAuthService, ttl) => new CachingExternalAuthorizationService(cacheableAuthService, ttl)
//          }
          null : ExternalAuthorizationService
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
  }

  private object defaults {
    val successHttpCode = 204
    val validate = true
  }

}
