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
package tech.beshu.ror.es.utils

import cats.Show
import cats.implicits.*
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.rest.*
import org.elasticsearch.rest.action.cat.RestCatAction
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError
import tech.beshu.ror.es.RorRestChannel
import tech.beshu.ror.es.actions.wrappers._cat.rest.RorWrappedRestCatAction
import tech.beshu.ror.es.utils.ThreadContextOps.createThreadContextOps
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.util
import scala.util.Try

class ChannelInterceptingRestHandlerDecorator private(val underlying: RestHandler)
  extends RestHandler with Logging {

  private val wrapped = doPrivileged {
    wrapSomeActions(underlying)
  }

  override def handleRequest(request: RestRequest, channel: RestChannel, client: NodeClient): Unit = {
    RorRestChannel.from(channel) match {
      case Right(rorRestChannel) =>
        ThreadRepo.safeSetRestChannel(rorRestChannel) {
          addXpackUserAuthenticationHeaderForInCaseOfSecurityRequest(request, client)
          wrapped.handleRequest(request, rorRestChannel, client)
        }
      case Left(error) =>
        logError(error)
        implicit val show: Show[AuthorizationValueError] = authorizationValueErrorSanitizedShow
        channel.sendResponse(new BytesRestResponse(channel, RestStatus.BAD_REQUEST, new ElasticsearchException(error.show)))
    }
  }

  override def canTripCircuitBreaker: Boolean = underlying.canTripCircuitBreaker

  override def supportsContentStream(): Boolean = underlying.supportsContentStream()

  override def getConcreteRestHandler: RestHandler = underlying.getConcreteRestHandler

  override def allowsUnsafeBuffers(): Boolean = underlying.allowsUnsafeBuffers()

  override def routes(): util.List[RestHandler.Route] = underlying.routes()

  override def allowSystemIndexAccessByDefault(): Boolean = underlying.allowSystemIndexAccessByDefault()

  private def wrapSomeActions(ofHandler: RestHandler) = {
    unwrapWithSecurityRestFilterIfNeeded(ofHandler) match {
      case action: RestCatAction => new RorWrappedRestCatAction(action)
      case action => action
    }
  }

  private def unwrapWithSecurityRestFilterIfNeeded(restHandler: RestHandler) = {
    restHandler match {
      case action if action.getClass.getName.contains("SecurityRestFilter") =>
        tryToGetUnderlyingRestHandler(action, "delegate")
          .getOrElse(action)
      case _ =>
        restHandler
    }
  }

  private def tryToGetUnderlyingRestHandler(restHandler: RestHandler,
                                            fieldName: String) = {
    Try(on(restHandler).get[RestHandler](fieldName))
  }

  private def addXpackUserAuthenticationHeaderForInCaseOfSecurityRequest(request: RestRequest,
                                                                         client: NodeClient): Unit = {
    if (request.path().contains("/_security") || request.path().contains("/_xpack/security")) {
      client
        .threadPool().getThreadContext
        .addXpackUserAuthenticationHeader(client.getLocalNodeId)
    }
  }

  private def logError(error: AuthorizationValueError): Unit = {
    {
      implicit val show: Show[AuthorizationValueError] = authorizationValueErrorSanitizedShow
      logger.warn(s"The incoming request was malformed. Cause: ${error.show}")
    }
    if (logger.delegate.isDebugEnabled()) {
      implicit val show: Show[AuthorizationValueError] = authorizationValueErrorWithDetailsShow
      logger.debug(s"Malformed request detailed cause: ${error.show}")
    }
  }

}

object ChannelInterceptingRestHandlerDecorator {
  def create(restHandler: RestHandler): ChannelInterceptingRestHandlerDecorator = restHandler match {
    case alreadyDecoratedHandler: ChannelInterceptingRestHandlerDecorator => alreadyDecoratedHandler
    case handler => new ChannelInterceptingRestHandlerDecorator(handler)
  }
}