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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.acl.blocks.Block.Verbosity._
import tech.beshu.ror.acl.blocks.Block.{ExecutionResult, Verbosity}
import tech.beshu.ror.acl.logging.ResponseContext._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.TaskOps._
import tech.beshu.ror.acl.{Acl, AclHandler}
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._
import scala.util.{Failure, Success}

class AclLoggingDecorator(underlying: Acl, auditingTool: Option[AuditingTool])
  extends Acl with Logging {

  override def handle(requestContext: RequestContext,
                      handler: AclHandler): Task[(Vector[Block.History], Block.ExecutionResult)] = {
    logger.debug(s"checking request: ${requestContext.id.show}")
    underlying
      .handle(requestContext, handler)
      .andThen {
        case Success((history, ExecutionResult.Matched(block, blockContext))) =>
          block.policy match {
            case Allow => log(Allowed(requestContext, block, blockContext, history))
            case Forbid => log(ForbiddenBy(requestContext, block, blockContext, history))
          }
        case Success((history, ExecutionResult.Unmatched)) =>
          log(Forbidden(requestContext, history))
        case Failure(ex) if handler.isNotFound(ex) =>
          log(NotFound(requestContext, ex))
        case Failure(ex) =>
          log(Errored(requestContext, ex))
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
        .timeout(500 millis)
        .runAsync {
          case Right(_) =>
          case Left(ex) =>
            logger.warn("Auditing issue", ex)
        }
    }
  }


  private def isLoggableEntry(context: ResponseContext): Boolean = {
    context match {
      case Allowed(_, block, _, _) if block.verbosity === Verbosity.Info => false
      case _: Allowed | _: ForbiddenBy | _: Forbidden | _: Errored | _: NotFound => true
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
      case NotFound(requestContext, _) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(None, Vector.empty)
        s"""${Constants.ANSI_RED}NOT_FOUND by not found req=${requestContext.show}${Constants.ANSI_RESET}"""
      case Errored(requestContext, _) =>
        implicit val requestShow: Show[RequestContext] = RequestContext.show(None, Vector.empty)
        s"""${Constants.ANSI_YELLOW}ERRORED by error req=${requestContext.show}${Constants.ANSI_RESET}"""
    }
  }
}
