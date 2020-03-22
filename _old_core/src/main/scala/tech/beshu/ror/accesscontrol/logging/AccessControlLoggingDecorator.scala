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
import tech.beshu.ror.accesscontrol.domain.{Header, Operation}
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

  override def handleRegularRequest[T <: Operation](requestContext: RequestContext[T]): Task[WithHistory[RegularRequestResult[T], T]] = {
    logger.debug(s"checking request: ${requestContext.id.show}")
    underlying
      .handleRegularRequest(requestContext)
      .andThen {
        case Success(resultWithHistory) =>
          resultWithHistory.result match {
            case allow: RegularRequestResult.Allow[T] =>
              log(AllowedBy(requestContext, allow.block, allow.blockContext, resultWithHistory.history))
            case forbiddenBy: RegularRequestResult.ForbiddenBy[T] =>
              log(ForbiddenBy(requestContext, forbiddenBy.block, forbiddenBy.blockContext, resultWithHistory.history))
            case RegularRequestResult.ForbiddenByMismatched(_) =>
              log(Forbidden(requestContext, resultWithHistory.history))
            case RegularRequestResult.IndexNotFound =>
              log(RequestedIndexNotExist(requestContext, resultWithHistory.history))
            case RegularRequestResult.Failed(ex) =>
              log(Errored(requestContext, ex))
            case RegularRequestResult.PassedThrough =>
            // ignore
          }
        case Failure(ex) =>
          logger.error("Request handling unexpected failure", ex)
      }
  }

  override def handleMetadataRequest[T <: Operation](requestContext: RequestContext[T]): Task[WithHistory[UserMetadataRequestResult, T]] = {
    logger.debug(s"checking user metadata request: ${requestContext.id.show}")
    underlying
      .handleMetadataRequest(requestContext)
      .andThen {
        case Success(resultWithHistory) =>
          resultWithHistory.result match {
            case UserMetadataRequestResult.Allow(userMetadata, block) =>
              log(Allow(requestContext, userMetadata, block, resultWithHistory.history))
            case UserMetadataRequestResult.Forbidden =>
              log(Forbidden(requestContext, resultWithHistory.history))
            case UserMetadataRequestResult.PassedThrough =>
            // ignore
          }
        case Failure(ex) =>
          logger.error("Request handling unexpected failure", ex)
      }
  }

  private def log[T <: Operation](responseContext: ResponseContext[T]): Unit = {
    if (isLoggableEntry(responseContext)) {
      implicit val showHeader: Show[Header] =
        if (logger.delegate.isDebugEnabled()) headerShow
        else obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)
      import tech.beshu.ror.accesscontrol.logging.AccessControlLoggingDecorator.responseContextShow
      logger.info(responseContextShow[T].show(responseContext))
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

  private def isLoggableEntry[T <: Operation](context: ResponseContext[T]): Boolean = {
    def shouldBeLogged(block: Block) = {
      block.verbosity match {
        case Verbosity.Info => true
        case Verbosity.Error => false
      }
    }

    context match {
      case AllowedBy(_, block, _, _) => shouldBeLogged(block)
      case Allow(_, _, _, _) => true
      case _: ForbiddenBy[T] | _: Forbidden[T] | _: Errored[T] | _: RequestedIndexNotExist[T] => true
    }
  }
}

object AccessControlLoggingDecorator {

  private implicit def responseContextShow[T <: Operation](implicit headerShow: Show[Header]): Show[ResponseContext[T]] = {
    Show.show[ResponseContext[T]] {
      case allowedBy: AllowedBy[T] =>
        implicit val requestShow: Show[RequestContext[T]] = RequestContext.show(
          allowedBy.blockContext.loggedUser, allowedBy.blockContext.kibanaIndex, allowedBy.history
        )
        s"""${Constants.ANSI_CYAN}ALLOWED by ${allowedBy.block.show} req=${allowedBy.requestContext.show}${Constants.ANSI_RESET}"""
      case allow: Allow[T] =>
        implicit val requestShow: Show[RequestContext[T]] = RequestContext.show(
          allow.userMetadata.loggedUser, allow.userMetadata.foundKibanaIndex, allow.history
        )
        s"""${Constants.ANSI_CYAN}ALLOWED by ${allow.block.show} req=${allow.requestContext.show}${Constants.ANSI_RESET}"""
      case forbiddenBy: ForbiddenBy[T] =>
        implicit val requestShow: Show[RequestContext[T]] = RequestContext.show(
          forbiddenBy.blockContext.loggedUser, forbiddenBy.blockContext.kibanaIndex, forbiddenBy.history
        )
        s"""${Constants.ANSI_PURPLE}FORBIDDEN by ${forbiddenBy.block.show} req=${forbiddenBy.requestContext.show}${Constants.ANSI_RESET}"""
      case forbidden: Forbidden[T] =>
        implicit val requestShow: Show[RequestContext[T]] = RequestContext.show(None, None, forbidden.history)
        s"""${Constants.ANSI_PURPLE}FORBIDDEN by default req=${forbidden.requestContext.show}${Constants.ANSI_RESET}"""
      case requestedIndexNotExist: RequestedIndexNotExist[T] =>
        implicit val requestShow: Show[RequestContext[T]] = RequestContext.show(None, None, requestedIndexNotExist.history)
        s"""${Constants.ANSI_PURPLE}INDEX NOT FOUND req=${requestedIndexNotExist.requestContext.show}${Constants.ANSI_RESET}"""
      case errored: Errored[T] =>
        implicit val requestShow: Show[RequestContext[T]] = RequestContext.show[T](None, None, Vector.empty)
        s"""${Constants.ANSI_YELLOW}ERRORED by error req=${errored.requestContext.show}${Constants.ANSI_RESET}"""
    }
  }
}
