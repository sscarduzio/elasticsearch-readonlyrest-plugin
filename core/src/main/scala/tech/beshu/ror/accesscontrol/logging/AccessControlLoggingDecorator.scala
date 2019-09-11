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
package tech.beshu.ror.accesscontrol.logging

import cats.Show
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.AccessControl.{RegularRequestResult, UserMetadataRequestResult, WithHistory}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.Verbosity
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.logging.ResponseContext._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.TaskOps._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

class AccessControlLoggingDecorator(val underlying: AccessControl, auditingTool: Option[AuditingTool])
                                   (implicit loggingContext: LoggingContext)
  extends AccessControl with Logging {

  override def handleRegularRequest(requestContext: RequestContext): Task[WithHistory[RegularRequestResult]] = {
    logger.debug(s"checking request: ${requestContext.id.show}")
    underlying
      .handleRegularRequest(requestContext)
      .andThen {
        case Success(resultWithHistory) =>
          resultWithHistory.result match {
            case RegularRequestResult.Allow(blockContext, block) =>
              log(AllowedBy(requestContext, block, blockContext, resultWithHistory.history))
            case RegularRequestResult.ForbiddenBy(blockContext, block) =>
              log(ForbiddenBy(requestContext, block, blockContext, resultWithHistory.history))
            case RegularRequestResult.ForbiddenByMismatched(_) =>
              log(Forbidden(requestContext, resultWithHistory.history))
            case RegularRequestResult.Failed(ex) =>
              log(Errored(requestContext, ex))
            case RegularRequestResult.PassedThrough =>
              // ignore
          }
        case Failure(ex) =>
          logger.error("Request handling unexpected failure", ex)
      }
  }

  override def handleMetadataRequest(requestContext: RequestContext): Task[WithHistory[UserMetadataRequestResult]] = {
    logger.debug(s"checking user metadata request: ${requestContext.id.show}")
    underlying
      .handleMetadataRequest(requestContext)
      .andThen {
        case Success(resultWithHistory) =>
          resultWithHistory.result match {
            case UserMetadataRequestResult.Allow(userMetadata) =>
              log(Allow(requestContext, userMetadata, resultWithHistory.history))
            case UserMetadataRequestResult.Forbidden =>
              log(Forbidden(requestContext, resultWithHistory.history))
            case UserMetadataRequestResult.PassedThrough =>
              // ignore
          }
        case Failure(ex) =>
          logger.error("Request handling unexpected failure", ex)
      }
  }

  private def log(responseContext: ResponseContext)
                 (implicit loggingContext: LoggingContext): Unit = {
    if (isLoggableEntry(responseContext)) {
      import loggingContext._
      import tech.beshu.ror.accesscontrol.logging.AccessControlLoggingDecorator.responseContextShow
      logger.info(responseContext.show)
    }
    auditingTool.foreach {
      _
        .audit(responseContext)
        .timeout(5 seconds)
        .runAsync {
          case Right(_) =>
          case Left(ex) =>
            logger.warn("Auditing issue", ex)
        }
      }
  }

  private def isLoggableEntry(context: ResponseContext): Boolean = {
    def shouldBeLogged(block: Block) = {
      block.verbosity match {
        case Verbosity.Info => true
        case Verbosity.Error => false
      }
    }
    context match {
      case AllowedBy(_, block, _, _) => shouldBeLogged(block)
      case Allow(_, _, _) => true
      case _: ForbiddenBy | _: Forbidden | _: Errored => true
    }
  }

}

object AccessControlLoggingDecorator {

  private implicit def responseContextShow(implicit headerShow:Show[Header]): Show[ResponseContext] = {
    Show.show {
      case AllowedBy(requestContext, block, blockContext, history) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(blockContext.loggedUser, blockContext.kibanaIndex, history)
        s"""${Constants.ANSI_CYAN}ALLOWED by ${block.show} req=${requestContext.show}${Constants.ANSI_RESET}"""
      case Allow(requestContext, metadata, history) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(metadata.loggedUser, metadata.foundKibanaIndex, history)
        s"""${Constants.ANSI_CYAN}ALLOWED req=${requestContext.show}${Constants.ANSI_RESET}"""
      case ForbiddenBy(requestContext, block, blockContext, history) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(blockContext.loggedUser, blockContext.kibanaIndex, history)
        s"""${Constants.ANSI_PURPLE}FORBIDDEN by ${block.show} req=${requestContext.show}${Constants.ANSI_RESET}"""
      case Forbidden(requestContext, history) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(None, None, history)
        s"""${Constants.ANSI_PURPLE}FORBIDDEN by default req=${requestContext.show}${Constants.ANSI_RESET}"""
      case Errored(requestContext, _) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(None, None, Vector.empty)
        s"""${Constants.ANSI_YELLOW}ERRORED by error req=${requestContext.show}${Constants.ANSI_RESET}"""
    }
  }
}
