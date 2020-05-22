/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import org.elasticsearch.rest.RestStatus
import org.elasticsearch.{ElasticsearchException, ElasticsearchSecurityException}

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
}
