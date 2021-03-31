/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

import org.elasticsearch.client.ResponseException
import org.elasticsearch.common.io.Streams
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.{ElasticsearchException, ElasticsearchSecurityException}
import ujson.Value

import scala.util.Try

object exceptions {

  case object NotDefinedForRorProxy extends ElasticsearchSecurityException("Invalid action for ROR proxy", RestStatus.NOT_IMPLEMENTED)

  final case class RorProxyException(message: String, cause: Throwable) extends Exception(message, cause) {
    def this(message: String) {
      this(message, null)
    }
  }
  object RorProxyException {
    def wrap(throwable: Throwable): RorProxyException = RorProxyException("ROR proxy internal error", throwable)
  }

  case object RorInternalException extends ElasticsearchException("ROR internal issue. Please report it")

  implicit class ElasticsearchExceptionOps(exception: Exception) {

    def toSpecializedException: Exception = {
      exception match {
        case e: ElasticsearchException => e.toSpecializedException
        case _ => exception
      }
    }
  }

  implicit class SpecializedElasticsearchExceptionOps(val exception: ElasticsearchException) {

    private val exceptionTypeCaptureRegex = """Elasticsearch exception \[type=(.*), reason=(.*)]""".r

    def toSpecializedException: ElasticsearchException = {
      exception.getMessage match {
        case exceptionTypeCaptureRegex(aType@"index_not_found_exception", reason) =>
          new ElasticsearchException(reason) {
            override def status(): RestStatus = RestStatus.NOT_FOUND

            override def getExceptionName: String = aType
          }
        case exceptionTypeCaptureRegex(aType, reason) =>
          new ElasticsearchException(reason) {
            override def status(): RestStatus = exception.status()

            override def getExceptionName: String = aType
          }
        case _ =>
          exception
      }
    }
  }

  implicit class SpecializedResponseExceptionOps(val exception: ResponseException) {
    def toSpecializedException: ElasticsearchException = {
      rootCauseError()
        .orElse(causedByError())
        .getOrElse(throw exception)
    }

    private def rootCauseError() = Try {
      error(json => json("error")("root_cause")(0))
    }

    private def causedByError() = Try {
      error(json => json("error")("caused_by"))
    }

    private def error(readExceptionJson: Value => Value) = {
      val responseJson = ujson.read(Streams.copyToString(
        new InputStreamReader(exception.getResponse.getEntity.getContent, StandardCharsets.UTF_8)
      ))
      val exceptionJson = readExceptionJson(responseJson)
      val `type` = exceptionJson("type").str
      val reason = exceptionJson("reason").str
      new ElasticsearchException(reason) {
        override def status(): RestStatus = RestStatus.fromCode(exception.getResponse.getStatusLine.getStatusCode)
        override def getExceptionName: String = `type`
      }
    }
  }
}
