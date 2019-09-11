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
package tech.beshu.ror.es.request.usermetadata

import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult
import tech.beshu.ror.accesscontrol.blocks.UserMetadata
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.boot.Engine
import tech.beshu.ror.configuration.LoggingContext
import tech.beshu.ror.es.request.{ForbiddenResponse, RequestInfo}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ScalaOps._

import scala.util.{Failure, Success, Try}

class CurrentUserMetadataRequestHandler[Request <: ActionRequest, Response <: ActionResponse](engine: Engine,
                                                                                              task: Task,
                                                                                              action: String,
                                                                                              request: Request,
                                                                                              baseListener: ActionListener[Response],
                                                                                              chain: ActionFilterChain[Request, Response],
                                                                                              channel: RestChannel,
                                                                                              threadPool: ThreadPool)
                                                                                             (implicit scheduler: Scheduler)
  extends Logging {

  def handle(requestInfo: RequestInfo, requestContext: RequestContext)
            (implicit loggingContext: LoggingContext): Unit = {
    engine.accessControl
      .handleMetadataRequest(requestContext)
      .runAsync {
        case Right(r) =>
          threadPool.getThreadContext.stashContext.bracket { _ =>
            commitResult(r.result, requestContext, requestInfo)
          }
        case Left(ex) =>
          baseListener.onFailure(new Exception(ex))
      }
  }

  private def commitResult(result: UserMetadataRequestResult,
                           requestContext: RequestContext,
                           requestInfo: RequestInfo): Unit = {
    Try {
      result match {
        case UserMetadataRequestResult.Allow(userMetadata) =>
          onAllow(requestContext, requestInfo, userMetadata)
        case UserMetadataRequestResult.Forbidden =>
          onForbidden()
        case UserMetadataRequestResult.PassedThrough =>
          proceed(baseListener)
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        logger.errorEx("ACL committing result failure", ex)
    }
  }

  private def onAllow(requestContext: RequestContext,
                      requestInfo: RequestInfo,
                      userMetadata: UserMetadata): Unit = {
    proceed {
      new CurrentUserMetadataResponseActionListener(
        baseListener.asInstanceOf[ActionListener[ActionResponse]],
        userMetadata
      ).asInstanceOf[ActionListener[Response]]
    }
  }

  private def onForbidden(): Unit = {
    channel.sendResponse(ForbiddenResponse.create(channel, Nil, engine.context))
  }

  private def proceed(responseActionListener: ActionListener[Response]): Unit =
    chain.proceed(task, action, request, responseActionListener)

}
