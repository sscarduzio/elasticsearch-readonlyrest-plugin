/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import org.elasticsearch.{ElasticsearchException, ElasticsearchSecurityException}
import org.elasticsearch.index.IndexNotFoundException
import org.elasticsearch.repositories.{RepositoryException, RepositoryMissingException}
import org.elasticsearch.rest.RestStatus

import scala.util.matching.Regex

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
    private val indexNameCaptureRegex = """no such index \[(.*)\]""".r
    private val missingRepositoryNameCaptureRegex = """\[(.*)\] missing""".r
    private val repositoryNameCaptureRegex = """\[(.*)\] (.*)""".r

    def toSpecializedException: ElasticsearchException = {
      exception.getMessage match {
        case exceptionTypeCaptureRegex(aType, reason) =>
          specializedExceptionByType(aType, reason) match {
            case Some(specialized) => specialized
            case None => exception
          }
        case _ => exception
      }
    }

    private def specializedExceptionByType(`type`: String, reason: String) = {
      `type` match {
        case "index_not_found_exception" =>
          Some(new IndexNotFoundException(captureOneGroup(indexNameCaptureRegex, reason)))
        case "repository_missing_exception" =>
          Some(new RepositoryMissingException(captureOneGroup(missingRepositoryNameCaptureRegex, reason)))
        case "repository_exception" =>
          val (repository, msg) = captureTwoGroups(repositoryNameCaptureRegex, reason)
          Some(new RepositoryException(repository, msg))
        case _ =>
          None
      }
    }

    private def captureOneGroup(regex: Regex, string: String) = {
      string match {
        case regex(captured) => captured
        case _ => "..."
      }
    }

    private def captureTwoGroups(regex: Regex, string: String) = {
      string match {
        case regex(captured1, captured2) => (captured1, captured2)
        case _ => ("...", "...")
      }
    }
  }
}
