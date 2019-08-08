package tech.beshu.ror.es.rradmin

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.adminapi.AdminRestApi

class RRAdminResponse(response: Either[Throwable, AdminRestApi.AdminResponse])
  extends ActionResponse with ToXContentObject with Logging {

  def this(response: AdminRestApi.AdminResponse) {
    this(Right(response))
  }

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    response match {
      case Right(adminResponse) =>
        adminResponse.result match {
          case AdminRestApi.Success(message) => addResponseJson(builder, "ok", message)
          case AdminRestApi.ConfigNotFound(message) => addResponseJson(builder, "empty", message)
          case AdminRestApi.Failure(message) => addResponseJson(builder, "ko", message)
        }
      case Left(ex) =>
        logger.error("RRAdmin internal error", ex)
        addResponseJson(builder, "ko", AdminRestApi.AdminResponse.internalError.result.message)
    }
    builder
  }

  private def addResponseJson(builder: XContentBuilder, status: String, message: String): Unit = {
    builder.startObject
    builder.field("status", status)
    builder.field("message", message)
    builder.endObject
  }
}
