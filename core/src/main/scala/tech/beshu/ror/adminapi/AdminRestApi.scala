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
package tech.beshu.ror.adminapi

import cats.Show
import cats.data.{EitherT, NonEmptyList}
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
import shapeless.HNil
import tech.beshu.ror.boot.RorInstance
import tech.beshu.ror.boot.RorInstance.IndexConfigReloadWithUpdateError.{IndexConfigSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.{IndexConfigReloadError, RawConfigReloadError}
import tech.beshu.ror.boot.SchedulerPools.adminRestApiScheduler
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.SpecializedError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError.IndexConfigNotExist
import tech.beshu.ror.configuration.{FileConfigLoader, IndexConfigManager, RawRorConfig}
import tech.beshu.ror.utils.ScalaOps._

class AdminRestApi(rorInstance: RorInstance,
                   indexConfigManager: IndexConfigManager,
                   fileConfigLoader: FileConfigLoader)
  extends EndpointModule[Task] {

  import AdminRestApi.encoders._
  import AdminRestApi._

  private val forceReloadRorEndpoint: Endpoint[Task, ApiCallResult] = post(forceReloadRorPath.endpointPath) {
    rorInstance
      .forceReloadFromIndex()
      .map {
        case Right(_) => Ok[ApiCallResult](Success("ReadonlyREST settings were reloaded with success!"))
        case Left(IndexConfigReloadError.LoadingConfigError(error)) => Ok(Failure(error.show))
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate)) => Ok(Failure("Current settings are already loaded"))
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)) => Ok(Failure("ROR is stopped"))
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(failure))) => Ok(Failure(s"Cannot reload new settings: ${failure.message}"))
      }
  }

  private val updateIndexConfigurationEndpoint = post(updateIndexConfigurationPath.endpointPath :: stringBody) { body: String =>
    val result = for {
      config <- rorConfigFrom(body)
      _ <- forceReloadAndSaveNewConfig(config)
    } yield ()
    result.value.map {
      case Right(_) => Ok[ApiCallResult](Success("updated settings"))
      case Left(failure) => Ok[ApiCallResult](failure)
    }
  }

  private val provideRorFileConfigEndpoint = get(provideRorFileConfigPath.endpointPath) {
    fileConfigLoader
      .load()
      .map {
        case Right(config) => Ok[ApiCallResult](Success(config.raw))
        case Left(error) => Ok[ApiCallResult](Failure(error.show))
      }
  }

  private val provideRorIndexConfigEndpoint = get(provideRorIndexConfigPath.endpointPath) {
    indexConfigManager
      .load()
      .map {
        case Right(config) =>
          Ok[ApiCallResult](Success(config.raw))
        case Left(SpecializedError(error: IndexConfigNotExist.type)) =>
          implicit val show = IndexConfigError.show.contramap(identity[IndexConfigNotExist.type])
          Ok[ApiCallResult](ConfigNotFound(error.show))
        case Left(error) =>
          Ok[ApiCallResult](Failure(error.show))
      }
  }

  private val service = {
    forceReloadRorEndpoint :+:
      updateIndexConfigurationEndpoint :+:
      provideRorFileConfigEndpoint :+:
      provideRorIndexConfigEndpoint
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
        liftFailure("Malformed settings payload - no settings key")
      case configJsonValue :: Nil  =>
        configJsonValue.asString match {
          case Some(configString) =>
            EitherT(RawRorConfig.fromString(configString).map(_.left.map(error => Failure(error.show))))
          case None =>
            liftFailure("Malformed settings payload - settings key value is not string")
        }
      case _ =>
        liftFailure("Malformed settings payload - only one settings value allowed")
    }
  }

  private def forceReloadAndSaveNewConfig(config: RawRorConfig) = {
    EitherT(rorInstance.forceReloadAndSave(config))
      .leftMap {
        case IndexConfigSavingError(error) =>
          Failure(s"Cannot save new settings: ${error.show}")
        case ReloadError(RawConfigReloadError.ConfigUpToDate) =>
          Failure(s"Current settings are already loaded")
        case ReloadError(RawConfigReloadError.RorInstanceStopped) =>
          Failure(s"ROR instance is being stopped")
        case ReloadError(RawConfigReloadError.ReloadingFailed(failure)) =>
          Failure(s"Cannot reload new settings: ${failure.message}")
      }
  }

}

object AdminRestApi extends Logging {

  val forceReloadRorPath: Path = Path.create(NonEmptyList.of("_readonlyrest", "admin", "refreshconfig"))
  val updateIndexConfigurationPath: Path = Path.create(NonEmptyList.of("_readonlyrest", "admin", "config" ))
  val provideRorFileConfigPath: Path = Path.create(NonEmptyList.of("_readonlyrest", "admin", "config", "file"))
  val provideRorIndexConfigPath: Path = Path.create(NonEmptyList.of("_readonlyrest", "admin", "config"))
  val provideRorConfigPath: Path = Path.create(NonEmptyList.of("_readonlyrest", "admin", "config", "load"))

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
  final case class ConfigNotFound(message: String) extends ApiCallResult
  final case class Failure(message: String) extends ApiCallResult

  final case class Path private(endpointPath: Endpoint[Task, HNil], endpointString: String)
  object Path {
    def create(paths: NonEmptyList[String]): Path = new Path(
      paths.map(EndpointModule.apply[Task].path(_)).reduceLeft(_ :: _),
      paths.map(p => s"/$p").toList.mkString
    )
  }

  object encoders {
    import io.circe.generic.semiauto._
    implicit val apiCallResultEncoder: Encoder[ApiCallResult] = deriveEncoder
    implicit val adminResponseEncoder: Encoder[AdminResponse] = deriveEncoder
  }

  object decoders {
    import io.circe.generic.semiauto._
    implicit val apiCallResultDecoder: Decoder[ApiCallResult] = deriveDecoder
    implicit val adminResponseDecoder: Decoder[AdminResponse] = deriveDecoder
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
