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
import tech.beshu.ror.accesscontrol.AccessControlList.{RegularRequestResult, UserMetadataRequestResult}
import tech.beshu.ror.accesscontrol.{AccessControlList, History}
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.blocks.Block.Verbosity
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.logging.ResponseContext.*
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.TaskOps.*

import scala.util.{Failure, Success}

class AccessControlListLoggingDecorator(val underlying: AccessControlList,
                                        auditingTool: Option[AuditingTool])
                                       (implicit loggingContext: LoggingContext,
                                        scheduler: Scheduler)
  extends AccessControlList with RequestIdAwareLogging {

  override def description: String = underlying.description

  override def handleRegularRequest[B <: BlockContext : BlockContextUpdater](requestContext: RequestContext.Aux[B]): Task[(RegularRequestResult[B], History[B])] = {
    implicit val requestContextImpl: RequestContext.Aux[B] = requestContext
    logger.debug(s"checking request ${requestContext.restRequest.method.show} ${requestContext.restRequest.path.show} ...")
    underlying
      .handleRegularRequest(requestContext)
      .andThen {
        case Success((result, history)) =>
          result match {
            case allow: RegularRequestResult.Allow[B] =>
              log(AllowedBy(requestContext, allow.blockContext, history))
            case forbiddenBy: RegularRequestResult.ForbiddenBy[B] =>
              log(ForbiddenBy(requestContext, forbiddenBy.blockContext, history))
            case RegularRequestResult.ForbiddenByMismatched(_) =>
              log(Forbidden(requestContext, history))
            case RegularRequestResult.IndexNotFound(_) =>
              log(RequestedIndexNotExist(requestContext, history))
            case RegularRequestResult.AliasNotFound() =>
              log(RequestedIndexNotExist(requestContext, history))
            case RegularRequestResult.TemplateNotFound() =>
              log(RequestedIndexNotExist(requestContext, history))
            case RegularRequestResult.Failed(ex) =>
              log(Errored(requestContext, ex))
            case RegularRequestResult.PassedThrough() =>
            // ignore
          }
        case Failure(ex) =>
          logger.error(s"Request handling unexpected failure", ex)
      }
  }

  // todo: logging metadata should be a little bit different
  override def handleMetadataRequest(requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]): Task[(UserMetadataRequestResult, History[UserMetadataRequestBlockContext])] = {
    implicit val requestContextImpl: RequestContext.Aux[UserMetadataRequestBlockContext] = requestContext
    logger.debug(s"checking user metadata request ...")
    underlying
      .handleMetadataRequest(requestContext)
      .andThen {
        case Success((result, history)) =>
          result match {
            case UserMetadataRequestResult.Allow(userMetadata) =>
              log(Allowed(requestContext, userMetadata, history))
            case forbiddenBy: UserMetadataRequestResult.ForbiddenBy =>
              log(ForbiddenBy(requestContext, forbiddenBy.blockContext, history))
            case UserMetadataRequestResult.ForbiddenByMismatched(_) =>
              log(Forbidden(requestContext, history))
            case UserMetadataRequestResult.PassedThrough =>
            // ignore
          }
        case Failure(ex) =>
          logger.error(s"Request handling unexpected failure", ex)
      }
  }

  private def log[B <: BlockContext](responseContext: ResponseContext[B]): Unit = {
    implicit val responseContextImpl: ResponseContext[B] = responseContext
    if (isLoggableEntry(responseContext)) {
      logger.info(logLevelDebugAwareResponseContextShow[B].show(responseContext))
    }
    blockAuditSettings(responseContext) match {
      case Some(Block.Audit.Disabled) =>
        ()
      case None | Some(Block.Audit.Enabled) =>
        auditingTool.foreach {
          _
            .audit(responseContext)
            .runAsync {
              case Right(_) =>
              case Left(ex) =>
                logger.warn(s"Auditing issue", ex)
            }
        }
    }
  }

  private implicit val showHeader: Show[Header] =
    if (logger.delegate.isDebugEnabled()) headerShow
    else obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)

  private def logLevelDebugAwareResponseContextShow[B <: BlockContext]: Show[ResponseContext[B]] = {
    responseContextShow(logger.delegate.isDebugEnabled())
  }

  private def blockAuditSettings[B <: BlockContext](responseContext: ResponseContext[B]): Option[Block.Audit] = {
    responseContext match {
      case AllowedBy(_, blockContext, _) => Some(blockContext.block.audit)
      case Allowed(_, userMetadata, _) =>
        userMetadata match {
          case UserMetadata.WithoutGroups(_, _, _, metadataOrigin) =>
            Some(metadataOrigin.blockContext.block.audit)
          case UserMetadata.WithGroups(groupsMetadata) =>
            val auditsFromGroupMetadataBlocks = groupsMetadata.values.map(_.metadataOrigin.blockContext.block.audit)
            Some {
              if (auditsFromGroupMetadataBlocks.exists(_ == Block.Audit.Enabled)) Block.Audit.Enabled
              else Block.Audit.Disabled
            }
        }
      case ForbiddenBy(_, blockContext, _) => Some(blockContext.block.audit)
      case Forbidden(_, _) => None
      case RequestedIndexNotExist(_, _) => None
      case Errored(_, _) => None
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
      case AllowedBy(_, blockContext, _) => shouldBeLogged(blockContext.block)
      case Allowed(_, _, _) => true
      case _: ForbiddenBy[_] | _: Forbidden[_] | _: Errored[_] | _: RequestedIndexNotExist[_] => true
    }
  }

  override val staticContext: AccessControlList.AccessControlStaticContext = underlying.staticContext
}

