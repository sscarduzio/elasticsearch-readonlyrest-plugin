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
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.{MetadataResponse, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.CorrelationId
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.accesscontrol.response.{ForbiddenResponseContext, RorKbnPluginNotSupported}
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext.Cause.fromMismatchedCause
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext.ForbiddenBlockMatch
import tech.beshu.ror.boot.ReadonlyRest.Engine
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.EsRequest
import tech.beshu.ror.es.handler.response.ForbiddenResponse
import tech.beshu.ror.es.handler.response.ForbiddenResponse.createRorNotEnabledResponse
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.time.{Duration, Instant}
import scala.util.{Failure, Success, Try}

class UserMetadataRequestHandler(engine: Engine,
                                 esContext: EsContext)
  extends RequestIdAwareLogging {

  def handle(request: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext] with EsRequest[UserMetadataRequestBlockContext]): Task[Unit] = {
    engine.core.accessControl
      .handleMetadataRequest(request)
      .map { case (result, _) => commitResult(result, request) }
  }

  private def commitResult(result: UserMetadataRequestResult,
                           request: UserMetadataRequestContext): Unit = {
    Try {
      result match {
        case UserMetadataRequestResult.RorKbnPluginNotSupported =>
          onForbidden(request, RorKbnPluginNotSupported.responseContext)
        case UserMetadataRequestResult.Allowed(userMetadata) =>
          onAllow(request, userMetadata)
        case UserMetadataRequestResult.Forbidden(blockContext) =>
          onForbidden(request, NonEmptyList.one(ForbiddenBlockMatch(blockContext.block)))
        case f@UserMetadataRequestResult.ForbiddenByMismatched(_) =>
          onForbidden(request, f.causes.toNonEmptyList.map(fromMismatchedCause))
        case UserMetadataRequestResult.PassedThrough =>
          onPassThrough(request)
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        implicit val requestContextImpl: RequestContext = request
        logger.errorEx(s"ACL committing result failure", ex)
        esContext.listener.onFailure(ex.asInstanceOf[Exception])
    }
  }

  private def onAllow(requestContext: UserMetadataRequestContext,
                      userMetadata: UserMetadata): Unit = {
    logRequestProcessingTime(requestContext)
    esContext.listener.onResponse(
      new RRMetadataResponse(requestContext.apiVersion, userMetadata, requestContext.currentGroupId, esContext.correlationId.value)
    )
  }

  private def onForbidden(requestContext: RequestContext, causes: NonEmptyList[ForbiddenResponseContext.Cause]): Unit =
    onForbidden(requestContext, ForbiddenResponseContext.from(causes, engine.core.accessControl.staticContext))

  private def onForbidden(requestContext: RequestContext, forbiddenResponseContext: ForbiddenResponseContext): Unit = {
    logRequestProcessingTime(requestContext)
    esContext.listener.onFailure(ForbiddenResponse.create(
      forbiddenResponseContext
    ))
  }

  private def onPassThrough(implicit requestContext: RequestContext): Unit = {
    logger.warn(s"Cannot handle the ${esContext.channel.restRequest.path.show} request because ReadonlyREST plugin was disabled in settings")
    esContext.listener.onFailure(createRorNotEnabledResponse())
  }

  private def logRequestProcessingTime(implicit requestContext: RequestContext): Unit = {
    logger.debug(s"Request processing time: ${Duration.between(requestContext.timestamp, Instant.now()).toMillis}ms")
  }

}

private class RRMetadataResponse(apiVersion: UserMetadataApiVersion,
                                 userMetadata: UserMetadata,
                                 currentGroupId: Option[GroupId],
                                 correlationId: CorrelationId)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val json = MetadataResponse.fromAsJavaJsonObject(apiVersion, userMetadata, currentGroupId, correlationId)
    builder.map(json)
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()
}