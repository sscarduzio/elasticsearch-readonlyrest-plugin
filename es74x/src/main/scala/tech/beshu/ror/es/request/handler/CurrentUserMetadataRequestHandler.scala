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
package tech.beshu.ror.es.request.handler

import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.{MetadataValue, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.CorrelationId
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.boot.Engine
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.EsRequest
import tech.beshu.ror.es.request.{ForbiddenResponse, RorNotAvailableResponse}
import tech.beshu.ror.utils.LoggerOps._
import scala.collection.JavaConverters._

import scala.util.{Failure, Success, Try}

class CurrentUserMetadataRequestHandler(engine: Engine,
                                        esContext: EsContext)
                                       (implicit scheduler: Scheduler)
  extends Logging {

  def handle(request: RequestContext.Aux[CurrentUserMetadataRequestBlockContext] with EsRequest[CurrentUserMetadataRequestBlockContext]): Task[Unit] = {
    engine.accessControl
      .handleMetadataRequest(request)
      .map { r => commitResult(r.result, request) }
  }

  private def commitResult(result: UserMetadataRequestResult,
                           request: RequestContext): Unit = {
    Try {
      result match {
        case UserMetadataRequestResult.Allow(userMetadata, _) =>
          onAllow(request, userMetadata)
        case UserMetadataRequestResult.Forbidden =>
          onForbidden()
        case UserMetadataRequestResult.PassedThrough =>
          onPassThrough()
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        logger.errorEx(s"[${request.id.show}] ACL committing result failure", ex)
        esContext.listener.onFailure(ex.asInstanceOf[Exception])
    }
  }

  private def onAllow(requestContext: RequestContext, userMetadata: UserMetadata): Unit = {
    esContext.listener.onResponse(new RRMetadataResponse(userMetadata, requestContext.correlationId))
  }

  private def onForbidden(): Unit = {
    esContext.channel.sendResponse(ForbiddenResponse.create(esContext.channel, Nil, engine.context))
  }

  private def onPassThrough(): Unit =
    esContext.channel.sendResponse(RorNotAvailableResponse.createRorNotEnabledResponse(esContext.channel))
}

private class RRMetadataResponse(userMetadata: UserMetadata,
                                 correlationId: CorrelationId)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val sourceMap: Map[String, _] = MetadataValue.read(userMetadata, correlationId).mapValues(MetadataValue.toAny)
    builder.map(sourceMap.asJava)
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()
}