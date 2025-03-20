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
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.AccessControlList
import tech.beshu.ror.accesscontrol.AccessControlList.{RegularRequestResult, UserMetadataRequestResult, WithHistory}
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.blocks.Block.Verbosity
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.logging.ResponseContext.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.constants
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.TaskOps.*

import scala.util.{Failure, Success}

class AccessControlListLoggingDecorator(val underlying: AccessControlList,
                                        auditingTool: Option[AuditingTool])
                                       (implicit loggingContext: LoggingContext,
                                        scheduler: Scheduler)
  extends AccessControlList with Logging {

  override def description: String = underlying.description

  override def handleRegularRequest[B <: BlockContext : BlockContextUpdater](requestContext: RequestContext.Aux[B]): Task[WithHistory[RegularRequestResult[B], B]] = {
    logger.debug(s"[${requestContext.id.show}] checking request ${requestContext.restRequest.method.show} ${requestContext.restRequest.path.show} ...")
    underlying
      .handleRegularRequest(requestContext)
      .andThen {
        case Success(resultWithHistory) =>
          resultWithHistory.result match {
            case allow: RegularRequestResult.Allow[B] =>
              log(AllowedBy(requestContext, allow.block, allow.blockContext, resultWithHistory.history))
            case forbiddenBy: RegularRequestResult.ForbiddenBy[B] =>
              log(ForbiddenBy(requestContext, forbiddenBy.block, forbiddenBy.blockContext, resultWithHistory.history))
            case RegularRequestResult.ForbiddenByMismatched(_) =>
              log(Forbidden(requestContext, resultWithHistory.history))
            case RegularRequestResult.IndexNotFound() =>
              log(RequestedIndexNotExist(requestContext, resultWithHistory.history))
            case RegularRequestResult.AliasNotFound() =>
              log(RequestedIndexNotExist(requestContext, resultWithHistory.history))
            case RegularRequestResult.TemplateNotFound() =>
              log(RequestedIndexNotExist(requestContext, resultWithHistory.history))
            case RegularRequestResult.Failed(ex) =>
              log(Errored(requestContext, ex))
            case RegularRequestResult.PassedThrough() =>
            // ignore
          }
        case Failure(ex) =>
          logger.error(s"[${requestContext.id.show}] Request handling unexpected failure", ex)
      }
  }

  // todo: logging metadata should be a little bit different
  override def handleMetadataRequest(requestContext: RequestContext.Aux[CurrentUserMetadataRequestBlockContext]): Task[WithHistory[UserMetadataRequestResult, CurrentUserMetadataRequestBlockContext]] = {
    logger.debug(s"[${requestContext.id.show}] checking user metadata request ...")
    underlying
      .handleMetadataRequest(requestContext)
      .andThen {
        case Success(resultWithHistory) =>
          resultWithHistory.result match {
            case UserMetadataRequestResult.Allow(userMetadata, block) =>
              log(Allow(requestContext, userMetadata, block, resultWithHistory.history))
            case UserMetadataRequestResult.Forbidden(_) =>
              log(Forbidden(requestContext, resultWithHistory.history))
            case UserMetadataRequestResult.PassedThrough =>
            // ignore
          }
        case Failure(ex) =>
          logger.error(s"[${requestContext.id.show}] Request handling unexpected failure", ex)
      }
  }

  private def log[B <: BlockContext](responseContext: ResponseContext[B]): Unit = {
    if (isLoggableEntry(responseContext)) {
      implicit val showHeader: Show[Header] =
        if (logger.delegate.isDebugEnabled()) headerShow
        else obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)
      import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator.responseContextShow
      logger.info(responseContextShow[B].show(responseContext))
    }
    auditingTool.foreach {
      _
        .audit(responseContext)
        .runAsync {
          case Right(_) =>
          case Left(ex) =>
            logger.warn(s"[${responseContext.requestContext.id.show}] Auditing issue", ex)
        }
    }
  }

  private def isLoggableEntry(context: ResponseContext[_]): Boolean = {
    def shouldBeLogged(block: Block) = {
      block.verbosity match {
        case Verbosity.Info => true
        case Verbosity.Error => false
      }
    }

    context match {
      case AllowedBy(_, block, _, _) => shouldBeLogged(block)
      case Allow(_, _, block, _) => shouldBeLogged(block)
      case _: ForbiddenBy[_] | _: Forbidden[_] | _: Errored[_] | _: RequestedIndexNotExist[_] => true
    }
  }

  override val staticContext: AccessControlList.AccessControlStaticContext = underlying.staticContext
}

object AccessControlListLoggingDecorator {

  private implicit def responseContextShow[B <: BlockContext](implicit headerShow: Show[Header]): Show[ResponseContext[B]] = {
    Show.show[ResponseContext[B]] {
      case allowedBy: AllowedBy[B] =>
        implicit val requestShow: Show[RequestContext.Aux[B]] = RequestContext.show(
          allowedBy.blockContext.userMetadata, allowedBy.history
        )
        s"""${constants.ANSI_CYAN}ALLOWED by ${allowedBy.block.show} req=${allowedBy.requestContext.show}${constants.ANSI_RESET}"""
      case allow: Allow[B] =>
        implicit val requestShow: Show[RequestContext.Aux[B]] = RequestContext.show(allow.userMetadata, allow.history)
        s"""${constants.ANSI_CYAN}ALLOWED by ${allow.block.show} req=${allow.requestContext.show}${constants.ANSI_RESET}"""
      case forbiddenBy: ForbiddenBy[B] =>
        implicit val requestShow: Show[RequestContext.Aux[B]] = RequestContext.show(
          forbiddenBy.blockContext.userMetadata, forbiddenBy.history
        )
        s"""${constants.ANSI_PURPLE}FORBIDDEN by ${forbiddenBy.block.show} req=${forbiddenBy.requestContext.show}${constants.ANSI_RESET}"""
      case forbidden: Forbidden[B] =>
        implicit val requestShow: Show[RequestContext.Aux[B]] = RequestContext.show(UserMetadata.empty, forbidden.history)
        s"""${constants.ANSI_PURPLE}FORBIDDEN by default req=${forbidden.requestContext.show}${constants.ANSI_RESET}"""
      case requestedIndexNotExist: RequestedIndexNotExist[B] =>
        implicit val requestShow: Show[RequestContext.Aux[B]] = RequestContext.show(UserMetadata.empty, requestedIndexNotExist.history)
        s"""${constants.ANSI_PURPLE}INDEX NOT FOUND req=${requestedIndexNotExist.requestContext.show}${constants.ANSI_RESET}"""
      case errored: Errored[B] =>
        implicit val requestShow: Show[RequestContext.Aux[B]] = RequestContext.show(UserMetadata.empty, Vector.empty)
        s"""${constants.ANSI_YELLOW}ERRORED by error req=${errored.requestContext.show}${constants.ANSI_RESET}"""
    }
  }
}
