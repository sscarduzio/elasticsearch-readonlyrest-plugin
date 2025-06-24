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
package tech.beshu.ror.es.handler

import cats.data.NonEmptyList
import cats.implicits.*
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.{MetadataValue, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.CorrelationId
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext.Cause.fromMismatchedCause
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext.ForbiddenBlockMatch
import tech.beshu.ror.boot.ReadonlyRest.Engine
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.EsRequest
import tech.beshu.ror.es.handler.response.ForbiddenResponse
import tech.beshu.ror.es.handler.response.ForbiddenResponse.createRorNotEnabledResponse
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.LoggerOps.*

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class CurrentUserMetadataRequestHandler(engine: Engine,
                                        esContext: EsContext)
  extends Logging {

  def handle(request: RequestContext.Aux[CurrentUserMetadataRequestBlockContext] with EsRequest[CurrentUserMetadataRequestBlockContext]): Task[Unit] = {
    engine.core.accessControl
      .handleMetadataRequest(request)
      .map { r => commitResult(r.result, request) }
  }

  private def commitResult(result: UserMetadataRequestResult,
                           request: RequestContext): Unit = {
    Try {
      result match {
        case UserMetadataRequestResult.Allow(userMetadata, _) =>
          onAllow(request, userMetadata)
        case UserMetadataRequestResult.ForbiddenBy(_, block) =>
          onForbidden(request, NonEmptyList.one(ForbiddenBlockMatch(block)))
        case UserMetadataRequestResult.ForbiddenByMismatched(causes) =>
          onForbidden(request, causes.toNonEmptyList.map(fromMismatchedCause))
        case UserMetadataRequestResult.PassedThrough =>
          onPassThrough(request)
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        logger.errorEx(s"[${request.id.toRequestId.show}] ACL committing result failure", ex)
        esContext.listener.onFailure(ex.asInstanceOf[Exception])
    }
  }

  private def onAllow(requestContext: RequestContext, userMetadata: UserMetadata): Unit = {
    logRequestProcessingTime(requestContext)
    esContext.listener.onResponse(new RRMetadataResponse(userMetadata, esContext.correlationId))
  }

  private def onForbidden(requestContext: RequestContext, causes: NonEmptyList[ForbiddenResponseContext.Cause]): Unit = {
    logRequestProcessingTime(requestContext)
    esContext.listener.onFailure(ForbiddenResponse.create(
      ForbiddenResponseContext.from(causes, engine.core.accessControl.staticContext)
    ))
  }

  private def onPassThrough(requestContext: RequestContext): Unit = {
    logger.warn(s"[${requestContext.id.toRequestId.show}] Cannot handle the ${esContext.channel.restRequest.path.show} request because ReadonlyREST plugin was disabled in settings")
    esContext.listener.onFailure(createRorNotEnabledResponse())
  }

  private def logRequestProcessingTime(requestContext: RequestContext): Unit = {
    logger.debug(s"[${requestContext.id.toRequestId.show}] Request processing time: ${Duration.between(requestContext.timestamp, Instant.now()).toMillis}ms")
  }

}

private class RRMetadataResponse(userMetadata: UserMetadata,
                                 correlationId: CorrelationId)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val sourceMap: Map[String, _] =
      MetadataValue.read(userMetadata, correlationId).view.mapValues(MetadataValue.toAny).toMap
    builder.map(sourceMap.asJava)
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()
}