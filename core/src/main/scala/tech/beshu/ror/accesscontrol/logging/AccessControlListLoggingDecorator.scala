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

import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.accesscontrol.AccessControlList.{RegularRequestResult, UserMetadataRequestResult}
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.logging.ResponseContext.*
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.accesscontrol.response.RorKbnPluginNotSupported
import tech.beshu.ror.accesscontrol.{AccessControlList, History}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.TaskOps.*

import scala.util.{Failure, Success}

class AccessControlListLoggingDecorator(val underlying: AccessControlList, auditingTool: AuditingTool)(
    implicit scheduler: Scheduler
) extends AccessControlList
    with RequestIdAwareLogging {

  override def description: String = underlying.description

  override def handleRegularRequest[B <: BlockContext: BlockContextUpdater](
      requestContext: RequestContext.Aux[B]
  ): Task[(RegularRequestResult[B], History[B])] = {
    implicit val requestContextImpl: RequestContext.Aux[B] = requestContext
    logger.debug(
      s"checking request ${requestContext.restRequest.method.show} ${requestContext.restRequest.path.show} ..."
    )
    underlying
      .handleRegularRequest(requestContext)
      .andThen {
        case Success((result, history)) =>
          result match {
            case allow: RegularRequestResult.Allowed[B] =>
              log(AllowedBy(requestContext, allow.matchedBlockContext, history))
            case forbiddenBy: RegularRequestResult.Forbidden[B] =>
              log(ForbiddenBy(requestContext, forbiddenBy.matchedBlockContext, history))
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
  override def handleMetadataRequest(
      requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]
  ): Task[(UserMetadataRequestResult, History[UserMetadataRequestBlockContext])] = {
    implicit val requestContextImpl: RequestContext.Aux[UserMetadataRequestBlockContext] = requestContext
    logger.debug(s"checking user metadata request ...")
    underlying
      .handleMetadataRequest(requestContext)
      .andThen {
        case Success((result, history)) =>
          result match {
            case UserMetadataRequestResult.Allowed(userMetadata) =>
              log(Allowed(requestContext, userMetadata, history))
            case forbiddenBy: UserMetadataRequestResult.Forbidden =>
              log(ForbiddenBy(requestContext, forbiddenBy.matchedBlockContext, history))
            case UserMetadataRequestResult.ForbiddenByMismatched(_) =>
              log(Forbidden(requestContext, history))
            case UserMetadataRequestResult.PassedThrough =>
            // ignore
            case UserMetadataRequestResult.RorKbnPluginNotSupported =>
              logger.warn(RorKbnPluginNotSupported.message)
              log(Forbidden(requestContext, History.empty))
          }
        case Failure(ex) =>
          logger.error(s"Request handling unexpected failure", ex)
      }
  }

  def withBlockTransformation(f: Block => Block): AccessControlList =
    new AccessControlListLoggingDecorator(underlying.withBlockTransformation(f), auditingTool)

  private def log[B <: BlockContext](responseContext: ResponseContext[B]): Unit = {
    given ResponseContext[B] = responseContext
    auditingTool
      .audit(responseContext)
      .runAsync {
        case Right(_) => ()
        case Left(ex) => logger.warn(s"Auditing issue", ex)
      }
  }

  override val staticContext: AccessControlList.AccessControlStaticContext = underlying.staticContext
}
