package tech.beshu.ror.adminapi

import cats.data.EitherT
import cats.implicits._
import com.twitter.finagle.http.Status.Successful
import com.twitter.finagle.http.{Request, Response}
import io.circe.parser._
import io.circe.{Decoder, Encoder}
import io.finch.Encode._
import io.finch._
import io.finch.circe._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.adminapi.AdminRestApi.{AdminRequest, AdminResponse, ApiCallResult, Failure, Success}
import tech.beshu.ror.boot.RorInstance
import tech.beshu.ror.boot.RorInstance.ForceReloadError
import tech.beshu.ror.boot.SchedulerPools.adminRestApiScheduler
import tech.beshu.ror.configuration.{IndexConfigManager, RawRorConfig}
import tech.beshu.ror.es.IndexJsonContentManager
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.YamlOps

import scala.language.{implicitConversions, postfixOps}

// todo: logging decorator
class AdminRestApi(rorInstance: RorInstance,
                   indexContentManager: IndexJsonContentManager)
  extends EndpointModule[Task] {

  import AdminRestApi.encoders._

  private val indexConfigManager = new IndexConfigManager(indexContentManager)

  private val forceReloadRorEndpoint: Endpoint[Task, ApiCallResult] = post("_readonlyrest" :: "admin" :: "refreshconfig") {
    rorInstance
      .forceReloadFromIndex()
      .map {
        case Right(_) => Ok[ApiCallResult](Success("ReadonlyREST config was reloaded with success!"))
        case Left(ForceReloadError.CannotReload(failure)) => Ok(Failure(failure.message))
        case Left(ForceReloadError.ReloadingError) => Ok(Failure("Reloading unexpected error"))
        case Left(ForceReloadError.StoppedInstance) => Ok(Failure("ROR is stopped"))
      }
  }

  private val updateIndexConfigurationEndpoint = post("_readonlyrest" :: "admin" :: "config" :: stringBody) { body: String =>
    val result = for {
      config <- rorConfigFrom(body)
      _ <- saveRorConfig(config)
    } yield ()
    result.value.map {
      case Right(_) => Ok[ApiCallResult](Success("updated settings"))
      case Left(failure) => Ok[ApiCallResult](failure)
    }
  }

  private val provideRorFileConfigEndpoint = get("_readonlyrest" :: "admin" :: "config" :: "file") {
    // todo: fixme file
    indexConfigManager
      .load()
      .map {
        case Right(config) => Ok[ApiCallResult](Success(YamlOps.jsonToYamlString(config.rawConfig)))
        case Left(error) => Ok[ApiCallResult](Failure(error.show))
      }
  }

  private val provideRorIndexConfigEndpoint = get("_readonlyrest" :: "admin" :: "config") {
    indexConfigManager
      .load()
      .map {
        case Right(config) => Ok[ApiCallResult](Success(YamlOps.jsonToYamlString(config.rawConfig)))
        case Left(error) => Ok[ApiCallResult](Failure(error.show))
      }
  }

  private val metadataEndpoint = get("_readonlyrest" :: "metadata" :: "current_user") {
    Ok[ApiCallResult](Success("will be filled"))
  }

  private val service = {
    forceReloadRorEndpoint :+:
      updateIndexConfigurationEndpoint :+:
      provideRorFileConfigEndpoint :+:
      provideRorIndexConfigEndpoint :+:
      metadataEndpoint
  }.toServiceAs[Application.Json]

  def call(request: AdminRequest): Task[AdminResponse] = {
    AdminRestApi.converters.toRequest(request) match {
      case Right(req) => Task.deferFuture(service.apply(req)).map(AdminRestApi.converters.toResponse)
      case Left(msg) => Task.now(AdminResponse(Failure(msg)))
    }
  }

  private def rorConfigFrom(payload: String) = {
    for {
      json <- EitherT.fromEither[Task](io.circe.parser.parse(payload).left.map(_ => Failure("JSON body malformed")))
      rorConfig <- settingsValue(json)
    } yield rorConfig
  }

  private def settingsValue(json: io.circe.Json) = {
    def liftFailure(failureMessage: String) = EitherT.leftT[Task, RawRorConfig](Failure(failureMessage))
    json \\ "settings" match {
      case Nil =>
        liftFailure("Malformed config payload - no settings key")
      case configJsonValue :: Nil  =>
        configJsonValue.asString match {
          case Some(configString) =>
            EitherT.fromEither[Task](RawRorConfig.fromString(configString).left.map(error => Failure(error.show)))
          case None =>
            liftFailure("Malformed config payload - settings key value is not string")
        }
      case _ =>
        liftFailure("Malformed config payload - only one settings value allowed")
    }
  }

  private def saveRorConfig(config: RawRorConfig) = {
    EitherT.right[Failure](indexConfigManager.save(config))
  }

}

object AdminRestApi extends Logging {

  final case class AdminRequest(method: String, uri: String, body: String)
  final case class AdminResponse(result: ApiCallResult)
  object AdminResponse {
    def notAvailable: AdminResponse = AdminResponse(Failure("Service not available"))
    def internalError: AdminResponse = AdminResponse(Failure("Internal error"))
  }

  sealed trait ApiCallResult {
    def message: String
  }
  final case class Success(message: String) extends ApiCallResult
  final case class Failure(message: String) extends ApiCallResult

  private object encoders {
    import io.circe.generic.semiauto._
    implicit val apiCallResultEncoder: Encoder[ApiCallResult] = deriveEncoder
  }

  private object decoders {
    import io.circe.generic.semiauto._
    implicit val apiCallResultDecoder: Decoder[ApiCallResult] = deriveDecoder
  }

  private object converters {
    def toRequest(request: AdminRequest): Either[String, Request] = {
      def requestCreator(uriString: String) = {
        request.method.toUpperCase() match {
          case "GET" => Right(Input.get(uriString))
          case "POST" => Right(Input.post(uriString))
          case _ => Left("Unsupported HTTP method")
        }
      }
      requestCreator(request.uri).map(_.withBody[Text.Plain](request.body)).map(_.request)
    }
    def toResponse(response: Response): AdminResponse = {
      response.status match {
        case Successful(_) =>
          (for {
            json <- parse(response.contentString)
            result <- decoders.apiCallResultDecoder.decodeJson(json)
          } yield result) match {
            case Right(result) => AdminResponse(result)
            case Left(ex) =>
              logger.warn("API internal error", ex)
              AdminResponse.internalError
          }
        case other =>
          AdminResponse(Failure(other.reason))
      }
    }
  }
}
