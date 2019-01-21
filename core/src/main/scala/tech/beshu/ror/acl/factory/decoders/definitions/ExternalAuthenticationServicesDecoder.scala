package tech.beshu.ror.acl.factory.decoders.definitions

import cats.implicits._
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthenticationService, ExternalAuthenticationService, ExternalAuthenticationServicesDefinitions, HttpExternalAuthenticationService}
import tech.beshu.ror.acl.factory.HttpClientFactory
import tech.beshu.ror.acl.factory.HttpClientFactory.Config
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.ScalaExt._
import tech.beshu.ror.acl.show.logs._

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object ExternalAuthenticationServicesDecoder {

  implicit val serviceNameDecoder: Decoder[ExternalAuthenticationService.Name] =
    DecoderHelpers.decodeStringLike.map(ExternalAuthenticationService.Name.apply)

  private implicit def externalAuthenticationServiceDecoder(implicit httpClientFactory: HttpClientFactory): Decoder[ExternalAuthenticationService] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder.instance { c =>
      for {
        name <- c.downField("name").as[ExternalAuthenticationService.Name]
        url <- c.downField("authentication_endpoint").as[Uri]
        httpSuccessCode <- c.downField("success_status_code").as[Option[Int]]
        cacheTtl <- c.downField("cache_ttl_in_sec").as[Option[FiniteDuration Refined Positive]]
        validate <- c.downField("validate").as[Option[Boolean]]
      } yield {
        val httpClient: HttpExternalAuthenticationService.HttpClient =
          httpClientFactory.create(Config(validate.getOrElse(consts.defaultValidate)))
        val externalAuthService: ExternalAuthenticationService =
          new HttpExternalAuthenticationService(name, url, httpSuccessCode.getOrElse(consts.defaultSuccessHttpCode), httpClient)
        cacheTtl.foldLeft(externalAuthService) {
          case (cacheableAuthService, ttl) => new CachingExternalAuthenticationService(cacheableAuthService, ttl)
        }
      }
    }
  }

  implicit def externalAuthenticationServicesDefinitionsDecoder(httpClientFactory: HttpClientFactory): Decoder[ExternalAuthenticationServicesDefinitions] = {
    implicit val _ = httpClientFactory
    DecoderHelpers
      .decodeFieldList[ExternalAuthenticationService]("external_authentication_service_configs")
      .emapE {
        case NoField => Right(ExternalAuthenticationServicesDefinitions(Set.empty[ExternalAuthenticationService]))
        case FieldListValue(Nil) => Left(DefinitionsCreationError(Message(s"External authentication services definitions declared, but no definition found")))
        case FieldListValue(list) =>
          list.map(_.name).findDuplicates match {
            case Nil =>
              Right(ExternalAuthenticationServicesDefinitions(list.toSet))
            case duplicates =>
              Left(DefinitionsCreationError(Message(s"External authentication services definitions must have unique names. Duplicates: ${duplicates.map(_.show).mkString(",")}")))
          }
      }
  }

  private object consts {
    val defaultSuccessHttpCode = 204
    val defaultValidate = true
  }
}
