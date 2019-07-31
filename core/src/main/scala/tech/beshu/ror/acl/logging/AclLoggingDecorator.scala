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
package tech.beshu.ror.acl.logging

import cats.Show
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.Block.Verbosity
import tech.beshu.ror.acl.logging.ResponseContext._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.utils.TaskOps._
import tech.beshu.ror.acl.{Acl, AclHandlingResult}
import tech.beshu.ror.acl.show.logs._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

class AclLoggingDecorator(val underlying: Acl, auditingTool: Option[AuditingTool])
  extends Acl with Logging {

  override def handle(requestContext: RequestContext): Task[AclHandlingResult] = {
    logger.debug(s"checking request: ${requestContext.id.show}")
    underlying
      .handle(requestContext)
      .andThen {
        case Success(result) =>
          result.handlingResult match {
            case Result.Allow(blockContext, block) =>
              log(Allowed(requestContext, block, blockContext, result.history))
            case Result.ForbiddenBy(blockContext, block) =>
              log(ForbiddenBy(requestContext, block, blockContext, result.history))
            case Result.ForbiddenByMismatched(_) =>
              log(Forbidden(requestContext, result.history))
            case Result.Failed(ex) =>
              log(Errored(requestContext, ex))
            case Result.PassedThrough =>
              // ignore
          }
      }
  }

  private def log(responseContext: ResponseContext): Unit = {
    if (isLoggableEntry(responseContext)) {
      import tech.beshu.ror.acl.logging.AclLoggingDecorator.responseContextShow
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
    context match {
      case Allowed(_, block, _, _) =>
        block.verbosity match {
          case Verbosity.Info => true
          case Verbosity.Error => false
        }
      case _: ForbiddenBy | _: Forbidden | _: Errored => true
    }
  }

}

object AclLoggingDecorator {

  private implicit val responseContextShow: Show[ResponseContext] = {
    Show.show {
      case Allowed(requestContext, block, blockContext, history) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(Some(blockContext), history)
        s"""${Constants.ANSI_CYAN}ALLOWED by ${block.show} req=${requestContext.show}${Constants.ANSI_RESET}"""
      case ForbiddenBy(requestContext, block, blockContext, history) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(Some(blockContext), history)
        s"""${Constants.ANSI_PURPLE}FORBIDDEN by ${block.show} req=${requestContext.show}${Constants.ANSI_RESET}"""
      case Forbidden(requestContext, history) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(None, history)
        s"""${Constants.ANSI_PURPLE}FORBIDDEN by default req=${requestContext.show}${Constants.ANSI_RESET}"""
      case Errored(requestContext, _) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(None, Vector.empty)
        s"""${Constants.ANSI_YELLOW}ERRORED by error req=${requestContext.show}${Constants.ANSI_RESET}"""
    }
  }
}
