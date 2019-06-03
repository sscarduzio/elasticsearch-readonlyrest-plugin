package tech.beshu.ror.adminapi

import com.twitter.finagle.http.{Method, Request, Response, Version}
import com.twitter.io.{Buf, BufReader}
import io.circe.{Encoder, Json}
import io.circe.syntax._
import io.finch._
import io.finch.circe._
import monix.eval.Task
import tech.beshu.ror.Constants.REST_REFRESH_PATH
import tech.beshu.ror.adminapi.AdminRestApi.{AdminRequest, AdminResponse, ApiCallResult}
import tech.beshu.ror.adminapi.AdminRestApi.ApiCallResult.{Failure, Success}
import tech.beshu.ror.adminapi.AdminRestApi._
import tech.beshu.ror.boot.RorInstance
import tech.beshu.ror.boot.RorInstance.ForceReloadError
import tech.beshu.ror.boot.SchedulerPools.adminRestApiScheduler
import tech.beshu.ror.utils.ScalaOps._

import scala.language.{implicitConversions, postfixOps}

class AdminRestApi(rorInstance: RorInstance) extends EndpointModule[Task] {

  import AdminRestApi.encoders._

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

  private val e2 = get("") { Ok[ApiCallResult](Success("Hello, World!")) }

  private val service = (forceReloadRorEndpoint :+: e2).toServiceAs[Application.Json]

  def call(request: AdminRequest): Task[AdminResponse] = {
    converters.toRequest(request) match {
      case Right(req) => Task.deferFuture(service.apply(req)).map(converters.toResponse)
      case Left(msg) => Task.now(AdminResponse(402, msg))
    }
  }

}

object AdminRestApi {

  final case class AdminRequest(method: String, uri: String, body: String)
  final case class AdminResponse(status: Int, body: String)
  object AdminResponse {
    def notAvailable: AdminResponse = AdminResponse(503, "Service not available")
  }

  sealed trait ApiCallResult
  object ApiCallResult {
    final case class Success(message: String) extends ApiCallResult
    final case class Failure(message: String) extends ApiCallResult
  }

  private object encoders {
    implicit val apiCallResultEncoder: Encoder[ApiCallResult] = Encoder.encodeJson.contramap {
      case ApiCallResult.Success(message) => resultJson("ok", message)
      case ApiCallResult.Failure(message) => resultJson("ko", message)
    }

    private def resultJson(status: String, message: String) = Json.obj(
      ("status", status.asJson),
      ("message", message.asJson),
    )
  }

  private object converters {
    def toRequest(request: AdminRequest): Either[String, Request] = {
      def getMethod() = {
        request.method.toUpperCase() match {
          case "GET" => Right(Method.Get)
          case "POST" => Right(Method.Post)
          case _ => Left("Unsupported HTTP method")
        }
      }
      for {
        method <- getMethod()
      } yield Request(Version.Http11, method, request.uri, BufReader(Buf.Utf8(request.body)))
    }
    def toResponse(response: Response): AdminResponse = AdminResponse(response.statusCode, response.contentString)
  }
}
