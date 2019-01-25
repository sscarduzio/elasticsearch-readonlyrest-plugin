package tech.beshu.ror.acl.factory.decoders.definitions

import com.jayway.jsonpath.JsonPath
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.blocks.definitions.HttpExternalAuthorizationService.SupportedHttpMethod.Get
import tech.beshu.ror.acl.blocks.definitions.HttpExternalAuthorizationService.{AuthTokenName, AuthTokenSendMethod, QueryParam, SupportedHttpMethod}
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthorizationService, ExternalAuthorizationService, HttpExternalAuthorizationService}
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.Config
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.common.decoderTupleList
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, _}

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scala.util.Try

class ExternalAuthorizationServicesDecoder(httpClientFactory: HttpClientsFactory)
  extends DefinitionsBaseDecoder[ExternalAuthorizationService]("user_groups_providers") (
    ExternalAuthorizationServicesDecoder.externalAuthorizationServiceDecoder(httpClientFactory)
  )

object ExternalAuthorizationServicesDecoder extends Logging {

  implicit val serviceNameDecoder: Decoder[ExternalAuthorizationService.Name] =
    DecoderHelpers.decodeStringLike.map(ExternalAuthorizationService.Name.apply)

  private implicit val authTokenNameDecoder: Decoder[AuthTokenName] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(AuthTokenName.apply)

  private implicit val sendMethodDecoder: Decoder[AuthTokenSendMethod] =
    Decoder.decodeString.emapE {
      case "HEADER" => Right(AuthTokenSendMethod.UsingHeader)
      case "QUERY_PARAM" => Right(AuthTokenSendMethod.UsingQueryParam)
      case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown value '$unknown' of 'auth_token_passed_as' attribute")))
    }

  private implicit val supportedHttpMethodDecoder: Decoder[SupportedHttpMethod] =
    Decoder.decodeString.emapE {
      case "POST" | "post" => Right(SupportedHttpMethod.Post)
      case "GET" | "get" => Right(Get)
      case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown value '$unknown' of 'http_method' attribute")))
    }

  private implicit val jsonPathDecoder: Decoder[JsonPath] =
    Decoder.decodeString.emapE { jsonPathStr =>
      Try(JsonPath.compile(jsonPathStr))
        .toEither
        .left
        .map { ex =>
          logger.error("JSON path compilation failed", ex)
          DefinitionsLevelCreationError(Message(s"Cannot compile '$jsonPathStr' to JSON path"))
        }
    }

  private implicit val headerSetDecoder: Decoder[Set[Header]] =
    decoderTupleList.map(_.map(Header.apply).toSet)

  private implicit val queryParamSetDecoder: Decoder[Set[QueryParam]] =
    decoderTupleList.map(_.map(t => QueryParam(t._1, t._2)).toSet)

  private implicit def externalAuthorizationServiceDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[ExternalAuthorizationService] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[ExternalAuthorizationService.Name]
          url <- c.downField("groups_endpoint").as[Uri]
          authTokenName <- c.downField("auth_token_name").as[AuthTokenName]
          sendUsing <- c.downField("auth_token_passed_as").as[AuthTokenSendMethod]
          httpMethod <- c.downField("http_method").as[Option[SupportedHttpMethod]]
          groupsJsonPath <- c.downField("response_groups_json_path").as[JsonPath]
          defaultQueryParams <- c.downField("default_query_parameters").as[Option[Set[QueryParam]]]
          defaultHeaders <- c.downField("default_headers").as[Option[Set[Header]]]
          cacheTtl <- c.downField("cache_ttl_in_sec").as[Option[FiniteDuration Refined Positive]]
          validate <- c.downField("validate").as[Option[Boolean]]
        } yield {
          val httpClient = httpClientFactory.create(Config(validate.getOrElse(defaults.validate)))
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
            case (cacheableAuthService, ttl) => new CachingExternalAuthorizationService(cacheableAuthService, ttl)
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
  }

  private object defaults {
    val httpMethod: SupportedHttpMethod = Get
    val validate = true
  }

}
