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
package tech.beshu.ror.es.request.handler.usermetadata

import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.boot.Engine
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.EsRequest
import tech.beshu.ror.es.request.{ForbiddenResponse, RorNotAvailableResponse}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ScalaOps._

import scala.util.{Failure, Success, Try}

class CurrentUserMetadataRequestHandler(engine: Engine,
                                        esContext: EsContext,
                                        threadPool: ThreadPool)
                                       (implicit scheduler: Scheduler)
  extends Logging {

  def handle(request: RequestContext.Aux[CurrentUserMetadataRequestBlockContext] with EsRequest[CurrentUserMetadataRequestBlockContext]): Task[Unit] = {
    engine.accessControl
      .handleMetadataRequest(request)
      .map { r =>
        threadPool.getThreadContext.stashContext.bracket { _ =>
          commitResult(r.result, request)
        }
      }
  }

  private def commitResult(result: UserMetadataRequestResult,
                           requestContext: RequestContext): Unit = {
    Try {
      result match {
        case UserMetadataRequestResult.Allow(userMetadata, _) =>
          onAllow(userMetadata)
        case UserMetadataRequestResult.Forbidden =>
          onForbidden()
        case UserMetadataRequestResult.PassedThrough =>
          onPassThrough()
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        logger.errorEx("ACL committing result failure", ex)
    }
  }

  private def onAllow(userMetadata: UserMetadata): Unit = {
    val responseActionListener = new CurrentUserMetadataResponseActionListener(esContext.listener, userMetadata)
    esContext.chain.proceed(esContext.task, esContext.actionType, esContext.actionRequest, responseActionListener)
  }

  private def onForbidden(): Unit = {
    esContext.channel.sendResponse(ForbiddenResponse.create(esContext.channel, Nil, engine.context))
  }

  private def onPassThrough(): Unit =
    esContext.channel.sendResponse(RorNotAvailableResponse.createRorNotEnabledResponse(esContext.channel))
}
